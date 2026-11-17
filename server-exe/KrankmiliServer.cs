// Krankmili Button Box server (Windows tray app).
// Plain C# 5, compiled with the csc.exe that ships with Windows (.NET Framework 4.x).
// Protocol: docs/PROTOCOL.md (newline-delimited JSON over TCP) + UDP auto-discovery on 48765.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.IO.MemoryMappedFiles;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Krankmili Button Box Server")]
[assembly: AssemblyDescription("Tray server that receives button presses from the Krankmili & O.K Button Box Android app and sends them to Euro Truck Simulator 2.")]
[assembly: AssemblyCompany("Krankmili TV")]
[assembly: AssemblyProduct("Krankmili Button Box")]
[assembly: AssemblyCopyright("Copyright (c) Krankmili TV")]
[assembly: AssemblyVersion("1.7.0.0")]
[assembly: AssemblyFileVersion("1.7.0.0")]

namespace Krankmili
{
    static class Const
    {
        public const int ProtocolVersion = 1;
        public const int IdleTimeoutMs = 8000;
        public const int HelloTimeoutMs = 5000;
        public const int DiscoveryPort = 48765;
        public const string DiscoveryQuery = "KRANKMILI_BB_DISCOVER";
        public const string AppName = "Krankmili Button Box";
        public const string Version = "1.7.0";
        public const string UpdateSource = "https://raw.githubusercontent.com/krankmilitv/krankmili-button-box/main/version.json";
    }

    // Looks at version.json in the project's GitHub repo; only notifies, never downloads or installs anything.
    static class UpdateCheck
    {
        public static string OffFile(string dir) { return Path.Combine(dir, "update-check.off"); }

        public static bool IsNewer(string a, string b)
        {
            string[] x = a.Split('.'), y = b.Split('.');
            for (int i = 0; i < Math.Max(x.Length, y.Length); i++)
            {
                int p = 0, q = 0;
                if (i < x.Length) int.TryParse(x[i], out p);
                if (i < y.Length) int.TryParse(y[i], out q);
                if (p != q) return p > q;
            }
            return false;
        }

        // Returns true and fills version/url/notes when a newer release exists; false on any failure.
        public static bool Fetch(out string version, out string url, out string notes)
        {
            version = url = notes = "";
            try
            {
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;   // TLS 1.2 (not in the .NET 4.0 enum)
                HttpWebRequest rq = (HttpWebRequest)WebRequest.Create(Const.UpdateSource);
                rq.Timeout = 6000; rq.ReadWriteTimeout = 6000; rq.UserAgent = "KrankmiliButtonBox/" + Const.Version;
                using (WebResponse rs = rq.GetResponse())
                using (StreamReader r = new StreamReader(rs.GetResponseStream(), Encoding.UTF8))
                {
                    var d = (IDictionary)new JavaScriptSerializer().DeserializeObject(r.ReadToEnd());
                    version = d.Contains("version") ? Convert.ToString(d["version"]) : "";
                    url = d.Contains("url") ? Convert.ToString(d["url"]) : "";
                    notes = d.Contains("notes") ? Convert.ToString(d["notes"]) : "";
                }
                return version.Length > 0 && IsNewer(version, Const.Version);
            }
            catch (Exception) { return false; }
        }
    }

    static class Log
    {
        static readonly object Gate = new object();
        public static string Path;
        public static void Info(string fmt, params object[] a)
        {
            string line = DateTime.Now.ToString("HH:mm:ss") + " " + (a.Length > 0 ? string.Format(fmt, a) : fmt);
            lock (Gate)
            {
                try
                {
                    // keep the log small: restart it when it passes 512 KB
                    FileInfo fi = new FileInfo(Path);
                    if (fi.Exists && fi.Length > 512 * 1024) File.Delete(Path);
                    File.AppendAllText(Path, line + Environment.NewLine, Encoding.UTF8);
                }
                catch (Exception) { }
            }
        }
    }

    // ---------------------------------------------------------------- scancodes
    struct Scan
    {
        public ushort Code; public bool Ext;
        public Scan(ushort c, bool e) { Code = c; Ext = e; }
    }

    static class KeyTable
    {
        public static readonly Dictionary<string, Scan> Map = Build();
        static readonly Dictionary<string, string> Alias = new Dictionary<string, string> {
            {"ctrl","lctrl"},{"shift","lshift"},{"alt","lalt"},{"return","enter"},{"escape","esc"},
            {"ins","insert"},{"del","delete"},{"pgup","pageup"},{"pgdn","pagedown"}
        };

        static Dictionary<string, Scan> Build()
        {
            var m = new Dictionary<string, Scan>();
            string digits = "1234567890";
            for (int i = 0; i < digits.Length; i++) m[digits[i].ToString()] = new Scan((ushort)(0x02 + i), false);
            string r1 = "qwertyuiop"; for (int i = 0; i < r1.Length; i++) m[r1[i].ToString()] = new Scan((ushort)(0x10 + i), false);
            string r2 = "asdfghjkl"; for (int i = 0; i < r2.Length; i++) m[r2[i].ToString()] = new Scan((ushort)(0x1E + i), false);
            string r3 = "zxcvbnm"; for (int i = 0; i < r3.Length; i++) m[r3[i].ToString()] = new Scan((ushort)(0x2C + i), false);
            for (int i = 0; i < 10; i++) m["f" + (i + 1)] = new Scan((ushort)(0x3B + i), false);
            m["f11"] = new Scan(0x57, false); m["f12"] = new Scan(0x58, false);
            Add(m, false, "esc", 0x01, "-", 0x0C, "=", 0x0D, "backspace", 0x0E, "tab", 0x0F, "[", 0x1A, "]", 0x1B,
                "enter", 0x1C, "lctrl", 0x1D, ";", 0x27, "'", 0x28, "`", 0x29, "lshift", 0x2A, "\\", 0x2B,
                ",", 0x33, ".", 0x34, "/", 0x35, "rshift", 0x36, "lalt", 0x38, "space", 0x39,
                "num*", 0x37, "num-", 0x4A, "num+", 0x4E, "num7", 0x47, "num8", 0x48, "num9", 0x49,
                "num4", 0x4B, "num5", 0x4C, "num6", 0x4D, "num1", 0x4F, "num2", 0x50, "num3", 0x51,
                "num0", 0x52, "num.", 0x53, "numlock", 0x45);
            Add(m, true, "num/", 0x35, "numenter", 0x1C, "rctrl", 0x1D, "ralt", 0x38, "home", 0x47, "up", 0x48,
                "pageup", 0x49, "left", 0x4B, "right", 0x4D, "end", 0x4F, "down", 0x50, "pagedown", 0x51,
                "insert", 0x52, "delete", 0x53);
            return m;
        }

        static void Add(Dictionary<string, Scan> m, bool ext, params object[] kv)
        {
            for (int i = 0; i < kv.Length; i += 2) m[(string)kv[i]] = new Scan((ushort)(int)kv[i + 1], ext);
        }

        public static List<string> Parse(string spec)
        {
            var names = new List<string>();
            string protectedSpec = spec.ToLowerInvariant().Replace("num+", "numplus");
            foreach (string raw in protectedSpec.Split('+'))
            {
                string part = raw.Trim();
                if (part == "numplus") part = "num+";
                string a;
                if (Alias.TryGetValue(part, out a)) part = a;
                if (!Map.ContainsKey(part)) throw new Exception("unknown key '" + part + "'");
                names.Add(part);
            }
            if (names.Count == 0) throw new Exception("empty key spec");
            return names;
        }
    }

    // ---------------------------------------------------------------- SendInput
    static class Native
    {
        const uint INPUT_KEYBOARD = 1, KEYEVENTF_EXTENDEDKEY = 1, KEYEVENTF_KEYUP = 2, KEYEVENTF_SCANCODE = 8;

        [StructLayout(LayoutKind.Sequential)]
        struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)]
        struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Explicit)]
        struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
        [StructLayout(LayoutKind.Sequential)]
        struct INPUT { public uint type; public InputUnion u; }

        [DllImport("user32.dll", SetLastError = true)]
        static extern uint SendInput(uint n, INPUT[] inputs, int size);

        public static DateTime LastSend = DateTime.MinValue;

        public static void Send(string name, bool down)
        {
            LastSend = DateTime.Now;
            Scan s = KeyTable.Map[name];
            uint flags = KEYEVENTF_SCANCODE;
            if (s.Ext) flags |= KEYEVENTF_EXTENDEDKEY;
            if (!down) flags |= KEYEVENTF_KEYUP;
            INPUT[] arr = new INPUT[1];
            arr[0].type = INPUT_KEYBOARD;
            arr[0].u.ki.wVk = 0;
            arr[0].u.ki.wScan = s.Code;
            arr[0].u.ki.dwFlags = flags;
            if (SendInput(1, arr, Marshal.SizeOf(typeof(INPUT))) != 1)
                Log.Info("SendInput failed for {0} (error {1})", name, Marshal.GetLastWin32Error());
        }
    }

    // ---------------------------------------------------------------- diagnostics
    // Windows (UIPI) silently drops injected keys when the game runs as administrator and we do not.
    static class Diag
    {
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool OpenProcessToken(IntPtr h, uint access, out IntPtr token);
        [DllImport("advapi32.dll", SetLastError = true)] static extern bool GetTokenInformation(IntPtr token, int cls, out int info, int len, out int ret);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);

        public static bool SelfElevated()
        {
            try { return new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator); }
            catch (Exception) { return false; }
        }

        // 0 = ETS2 not running, 1 = normal, 2 = elevated (or not inspectable from a normal process)
        public static int Ets2State()
        {
            Process[] ps = Process.GetProcessesByName("eurotrucks2");
            if (ps.Length == 0) return 0;
            IntPtr h = OpenProcess(0x1000, false, ps[0].Id);   // PROCESS_QUERY_LIMITED_INFORMATION
            if (h == IntPtr.Zero) return 2;
            try
            {
                IntPtr tok;
                if (!OpenProcessToken(h, 8, out tok)) return 2;    // TOKEN_QUERY; denied => higher integrity
                try { int el, ret; return GetTokenInformation(tok, 20, out el, 4, out ret) && el != 0 ? 2 : 1; }   // TokenElevation
                finally { CloseHandle(tok); }
            }
            finally { CloseHandle(h); }
        }

        public static string Report(Config cfg, int clients)
        {
            int e = Ets2State(); bool me = SelfElevated();
            var sb = new StringBuilder();
            sb.AppendLine("Bağlı cihaz: " + clients);
            sb.AppendLine("ETS2: " + (e == 0 ? "çalışmıyor" : e == 1 ? "çalışıyor (normal yetki)" : "çalışıyor (YÖNETİCİ yetkisi)"));
            sb.AppendLine("Bu sunucu: " + (me ? "yönetici" : "normal yetki"));
            sb.AppendLine("Son tuş gönderimi: " + (Native.LastSend == DateTime.MinValue ? "henüz yok" : Native.LastSend.ToString("HH:mm:ss")));
            if (e == 2 && !me)
                sb.AppendLine("\nSORUN: ETS2 yönetici olarak çalışıyor, sunucu değil. Windows bu durumda tuşları oyuna iletmez.\nÇözüm: tepsi simgesi → 'Yönetici olarak yeniden başlat', ya da ETS2'yi normal yetkiyle başlat.");
            else if (e == 0)
                sb.AppendLine("\nNot: ETS2 şu an çalışmıyor; tuşlar yalnızca oyun penceresi öndeyken işe yarar.");
            return sb.ToString();
        }
    }


    // Reference-counted key state shared by all clients.
    class KeyState
    {
        readonly Dictionary<string, int> counts = new Dictionary<string, int>();
        readonly object gate = new object();
        public bool DryRun;

        void Emit(string name, bool down)
        {
            if (DryRun) Log.Info("[dry-run] {0} {1}", name, down ? "DOWN" : "UP"); else Native.Send(name, down);
        }
        public void Down(string name)
        {
            lock (gate)
            {
                int n; counts.TryGetValue(name, out n);
                counts[name] = n + 1;
                if (n == 0) Emit(name, true);
            }
        }
        public void Up(string name)
        {
            lock (gate)
            {
                int n; counts.TryGetValue(name, out n);
                if (n <= 0) return;
                if (n == 1) { counts.Remove(name); Emit(name, false); } else counts[name] = n - 1;
            }
        }
    }

    // ---------------------------------------------------------------- config
    class Btn
    {
        public string Id, Label, Mode, Icon, Sub; public List<string> Keys; public int Span; public bool Accent;
        public List<List<string>> Steps; public int StepMs = 350;          // optional macro: key steps pressed one after another
        public string PromptText, PromptYes, PromptNo;                      // optional on-tablet confirm dialog shown after the press
    }
    class Grp
    {
        public string Title, Kind; public int Page, Column, Columns; public double Weight; public List<Btn> Buttons = new List<Btn>();
    }

    class Config
    {
        public int Port = 5555; public string Token = ""; public int TapMs = 60;
        public List<Grp> Groups = new List<Grp>();
        public Dictionary<string, Btn> Buttons = new Dictionary<string, Btn>();

        static string S(IDictionary d, string k, string def) { return d.Contains(k) && d[k] != null ? Convert.ToString(d[k], CultureInfo.InvariantCulture) : def; }
        static int I(IDictionary d, string k, int def) { return d.Contains(k) && d[k] != null ? Convert.ToInt32(d[k], CultureInfo.InvariantCulture) : def; }
        static double D(IDictionary d, string k, double def) { return d.Contains(k) && d[k] != null ? Convert.ToDouble(d[k], CultureInfo.InvariantCulture) : def; }
        static bool B(IDictionary d, string k) { return d.Contains(k) && d[k] is bool && (bool)d[k]; }

        public static Config Load(string json)
        {
            var ser = new JavaScriptSerializer();
            var raw = (IDictionary)ser.DeserializeObject(json);
            var c = new Config();
            c.Port = I(raw, "port", 5555); c.Token = S(raw, "token", ""); c.TapMs = I(raw, "tap_ms", 60);
            if (raw.Contains("groups"))
                foreach (IDictionary g in (IEnumerable)raw["groups"])
                {
                    var grp = new Grp();
                    grp.Title = S(g, "title", ""); grp.Kind = S(g, "kind", "buttons");
                    if (grp.Kind != "buttons" && grp.Kind != "status") throw new Exception("group '" + grp.Title + "': kind must be buttons/status");
                    grp.Page = I(g, "page", 1); grp.Column = I(g, "column", 1); grp.Columns = I(g, "columns", 2); grp.Weight = D(g, "weight", 1);
                    if (g.Contains("buttons"))
                        foreach (IDictionary b in (IEnumerable)g["buttons"])
                        {
                            var btn = new Btn();
                            btn.Id = S(b, "id", "");
                            if (c.Buttons.ContainsKey(btn.Id)) throw new Exception("duplicate button id '" + btn.Id + "'");
                            btn.Mode = S(b, "mode", "tap");
                            if (btn.Mode != "tap" && btn.Mode != "hold") throw new Exception("button '" + btn.Id + "': mode must be tap/hold");
                            try
                            {
                                if (b.Contains("seq") && b["seq"] is IEnumerable && !(b["seq"] is string))
                                {
                                    btn.Steps = new List<List<string>>();
                                    foreach (object step in (IEnumerable)b["seq"]) btn.Steps.Add(KeyTable.Parse(Convert.ToString(step, CultureInfo.InvariantCulture)));
                                    if (btn.Steps.Count == 0) throw new Exception("empty seq");
                                    btn.StepMs = I(b, "seq_ms", 350);
                                    btn.Keys = new List<string>();
                                }
                                else btn.Keys = KeyTable.Parse(S(b, "key", ""));
                            }
                            catch (Exception ex) { throw new Exception("button '" + btn.Id + "': " + ex.Message); }
                            if (b.Contains("prompt") && b["prompt"] is IDictionary)
                            {
                                IDictionary pr = (IDictionary)b["prompt"];
                                btn.PromptText = S(pr, "text", ""); btn.PromptYes = S(pr, "yes", ""); btn.PromptNo = S(pr, "no", "");
                            }
                            btn.Label = S(b, "label", btn.Id); btn.Icon = S(b, "icon", ""); btn.Sub = S(b, "sub", "");
                            btn.Span = I(b, "span", 1); btn.Accent = B(b, "accent");
                            c.Buttons[btn.Id] = btn; grp.Buttons.Add(btn);
                        }
                    c.Groups.Add(grp);
                }
            foreach (Btn pb in c.Buttons.Values)
                if (pb.PromptText != null && (!c.Buttons.ContainsKey(pb.PromptYes) || (pb.PromptNo.Length > 0 && !c.Buttons.ContainsKey(pb.PromptNo))))
                    throw new Exception("button '" + pb.Id + "': prompt yes/no must name existing button ids");
            return c;
        }

        public string LayoutJson()
        {
            var groups = new List<object>();
            foreach (Grp g in Groups)
            {
                var bl = new List<object>();
                foreach (Btn b in g.Buttons)
                {
                    var bd = new Dictionary<string, object> {
                        {"id", b.Id}, {"label", b.Label}, {"mode", b.Mode}, {"icon", b.Icon},
                        {"sub", b.Sub}, {"span", b.Span}, {"accent", b.Accent} };
                    if (b.PromptText != null)
                        bd["prompt"] = new Dictionary<string, object> { {"text", b.PromptText}, {"yes", b.PromptYes}, {"no", b.PromptNo} };
                    bl.Add(bd);
                }
                groups.Add(new Dictionary<string, object> {
                    {"title", g.Title}, {"kind", g.Kind}, {"page", g.Page}, {"column", g.Column}, {"columns", g.Columns},
                    {"weight", g.Weight}, {"buttons", bl} });
            }
            return new JavaScriptSerializer().Serialize(new Dictionary<string, object> {
                {"t", "layout"}, {"v", Const.ProtocolVersion}, {"groups", groups} });
        }
    }

    // ---------------------------------------------------------------- server
    class Session
    {
        readonly Config cfg; readonly KeyState keys;
        readonly HashSet<string> held = new HashSet<string>();
        readonly object gate = new object();
        public Session(Config c, KeyState k) { cfg = c; keys = k; }

        void DownAll(Btn b) { foreach (string k in b.Keys) keys.Down(k); }
        void UpAll(Btn b) { for (int i = b.Keys.Count - 1; i >= 0; i--) keys.Up(b.Keys[i]); }

        public void Press(string id)
        {
            Btn b;
            if (!cfg.Buttons.TryGetValue(id, out b)) { Log.Info("press for unknown button '{0}'", id); return; }
            if (b.Steps != null)
            {
                ThreadPool.QueueUserWorkItem(delegate
                {
                    for (int si = 0; si < b.Steps.Count; si++)
                    {
                        List<string> step = b.Steps[si];
                        foreach (string k in step) keys.Down(k);
                        try { Thread.Sleep(cfg.TapMs); } finally { for (int i = step.Count - 1; i >= 0; i--) keys.Up(step[i]); }
                        if (si < b.Steps.Count - 1) Thread.Sleep(b.StepMs);
                    }
                });
            }
            else if (b.Mode == "tap")
            {
                ThreadPool.QueueUserWorkItem(delegate
                {
                    DownAll(b);
                    try { Thread.Sleep(cfg.TapMs); } finally { UpAll(b); }
                });
            }
            else
            {
                lock (gate) { if (!held.Add(id)) return; }
                DownAll(b);
            }
        }

        public void Release(string id)
        {
            Btn b;
            if (!cfg.Buttons.TryGetValue(id, out b) || b.Mode != "hold") return;
            lock (gate) { if (!held.Remove(id)) return; }
            UpAll(b);
        }

        // Safety net: a dropped Wi-Fi link must never leave a window key stuck.
        public void ReleaseAll()
        {
            string[] ids; lock (gate) { ids = new string[held.Count]; held.CopyTo(ids); }
            foreach (string id in ids) Release(id);
        }
    }

    class Server
    {
        readonly Config cfg; readonly KeyState keys;
        TcpListener listener; UdpClient udp;
        public int Clients;
        public static readonly List<StreamWriter> Subs = new List<StreamWriter>();
        public Server(Config c, KeyState k) { cfg = c; keys = k; }

        public void Start()
        {
            listener = new TcpListener(IPAddress.Any, cfg.Port);
            listener.Start();
            var t = new Thread(AcceptLoop); t.IsBackground = true; t.Start();
            try
            {
                udp = new UdpClient();
                udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                udp.Client.Bind(new IPEndPoint(IPAddress.Any, Const.DiscoveryPort));
                var u = new Thread(DiscoveryLoop); u.IsBackground = true; u.Start();
                Log.Info("auto-discovery active (UDP {0})", Const.DiscoveryPort);
            }
            catch (Exception ex) { Log.Info("auto-discovery disabled: {0}", ex.Message); }
        }

        public void Stop()
        {
            try { listener.Stop(); } catch (Exception) { }
            try { udp.Close(); } catch (Exception) { }
        }

        void DiscoveryLoop()
        {
            string reply = new JavaScriptSerializer().Serialize(new Dictionary<string, object> {
                {"app", "krankmili-buttonbox"}, {"v", Const.ProtocolVersion}, {"port", cfg.Port},
                {"name", Environment.MachineName}, {"token", cfg.Token.Length > 0} });
            byte[] rb = Encoding.UTF8.GetBytes(reply);
            while (true)
            {
                try
                {
                    IPEndPoint from = new IPEndPoint(IPAddress.Any, 0);
                    byte[] data = udp.Receive(ref from);
                    if (Encoding.UTF8.GetString(data).Trim() == Const.DiscoveryQuery)
                    {
                        udp.Send(rb, rb.Length, from);
                        Log.Info("discovery request from {0}", from.Address);
                    }
                }
                catch (ObjectDisposedException) { return; }
                catch (SocketException) { if (udp.Client == null) return; }
                catch (Exception) { }
            }
        }

        void AcceptLoop()
        {
            while (true)
            {
                TcpClient c;
                try { c = listener.AcceptTcpClient(); }
                catch (Exception) { return; }
                var t = new Thread(delegate () { Handle(c); }); t.IsBackground = true; t.Start();
            }
        }

        static bool SafeEq(string a, string b)
        {
            if (a.Length != b.Length) return false;
            int d = 0; for (int i = 0; i < a.Length; i++) d |= a[i] ^ b[i];
            return d == 0;
        }

        public static void Send(StreamWriter w, string json) { lock (w) { w.Write(json + "\n"); w.Flush(); } }

        static IDictionary ReadMsg(StreamReader r, NetworkStream ns, int timeoutMs)
        {
            var ser = new JavaScriptSerializer();
            ns.ReadTimeout = timeoutMs;
            while (true)
            {
                string line = r.ReadLine();
                if (line == null) return null;
                try
                {
                    IDictionary m = ser.DeserializeObject(line) as IDictionary;
                    if (m != null) return m;
                }
                catch (Exception) { Log.Info("ignoring malformed line"); }
            }
        }

        void Handle(TcpClient client)
        {
            string peer = client.Client.RemoteEndPoint.ToString();
            Log.Info("client connected: {0}", peer);
            Interlocked.Increment(ref Clients);
            var session = new Session(cfg, keys);
            StreamWriter subWriter = null;
            try
            {
                client.NoDelay = true;
                NetworkStream ns = client.GetStream();
                var reader = new StreamReader(ns, new UTF8Encoding(false));
                var writer = new StreamWriter(ns, new UTF8Encoding(false));
                var ser = new JavaScriptSerializer();
                IDictionary hello = ReadMsg(reader, ns, Const.HelloTimeoutMs);
                if (hello == null || !hello.Contains("t") || (string)hello["t"] != "hello")
                { Send(writer, "{\"t\":\"error\",\"msg\":\"expected hello\"}"); return; }
                string tok = hello.Contains("token") && hello["token"] != null ? hello["token"].ToString() : "";
                if (cfg.Token.Length > 0 && !SafeEq(tok, cfg.Token))
                { Log.Info("bad token from {0}", peer); Send(writer, "{\"t\":\"error\",\"msg\":\"bad token\"}"); return; }
                Send(writer, cfg.LayoutJson());
                lock (Subs) { Subs.Add(writer); }
                subWriter = writer;
                while (true)
                {
                    IDictionary m = ReadMsg(reader, ns, Const.IdleTimeoutMs);
                    if (m == null) break;
                    string kind = m.Contains("t") ? Convert.ToString(m["t"]) : "";
                    string id = m.Contains("id") ? Convert.ToString(m["id"]) : "";
                    if (kind == "press") session.Press(id);
                    else if (kind == "release") session.Release(id);
                    else if (kind == "ping") Send(writer, "{\"t\":\"pong\"}");
                }
            }
            catch (Exception ex) { Log.Info("client {0} dropped: {1}", peer, ex.Message); }
            finally
            {
                session.ReleaseAll();
                if (subWriter != null) lock (Subs) { Subs.Remove(subWriter); }
                try { client.Close(); } catch (Exception) { }
                Interlocked.Decrement(ref Clients);
                Log.Info("client disconnected: {0}", peer);
            }
        }
    }

    // ---------------------------------------------------------------- telemetry
    // Reads the shared memory of RenCloud/scs-sdk-plugin (MIT), "Local\SCSTelemetry".
    // Offsets come from scs-telemetry-common.hpp (PLUGIN_REVID 12, plugin V.1.12.1).
    static class Telemetry
    {
        public static volatile bool PluginInstalled;
        const string MapName = "Local\\SCSTelemetry";

        static string Str(MemoryMappedViewAccessor a, int off, int len)
        {
            byte[] b = new byte[len];
            a.ReadArray(off, b, 0, len);
            int n = Array.IndexOf(b, (byte)0); if (n < 0) n = len;
            return Encoding.UTF8.GetString(b, 0, n);
        }

        public static string Snapshot()
        {
            var d = new Dictionary<string, object>();
            d["t"] = "status";
            d["plugin"] = PluginInstalled;
            d["game"] = false;
            try
            {
                using (MemoryMappedFile mmf = MemoryMappedFile.OpenExisting(MapName, MemoryMappedFileRights.Read))
                using (MemoryMappedViewAccessor a = mmf.CreateViewAccessor(0, 8192, MemoryMappedFileAccess.Read))
                {
                    bool active = a.ReadBoolean(0);
                    d["game"] = active;
                    if (active)
                    {
                        d["paused"] = a.ReadBoolean(4);
                        d["speed"] = Math.Round(Math.Abs(a.ReadSingle(948)) * 3.6, 0);   // truck_f.speed, m/s
                        d["fuel"] = Math.Round(a.ReadSingle(1000), 0);                    // truck_f.fuel, liters
                        d["fuelCap"] = Math.Round(a.ReadSingle(704), 0);                  // config_f.fuelCapacity
                        d["range"] = Math.Round(a.ReadSingle(1008), 0);                   // truck_f.fuelRange, km
                        d["wearEngine"] = Math.Round(a.ReadSingle(1036), 3);
                        d["wearTrans"] = Math.Round(a.ReadSingle(1040), 3);
                        d["wearChassis"] = Math.Round(a.ReadSingle(1048), 3);
                        d["wearWheels"] = Math.Round(a.ReadSingle(1052), 3);
                        d["routeKm"] = Math.Round(a.ReadSingle(1060), 0);                 // truck_f.routeDistance, meters
                        d["cargoKg"] = Math.Round(a.ReadSingle(748), 0);                  // config_f.cargoMass
                        d["cargoLoaded"] = a.ReadBoolean(1564);                           // config_b.isCargoLoaded
                        d["cargo"] = Str(a, 2620, 64);                                    // config_s.cargo
                        d["dest"] = Str(a, 2748, 64);                                     // config_s.cityDst
                    }
                }
            }
            catch (FileNotFoundException) { }
            catch (Exception ex) { Log.Info("telemetry read failed: {0}", ex.Message); }
            return new JavaScriptSerializer().Serialize(d);
        }

        public static void Loop()
        {
            while (true)
            {
                try
                {
                    StreamWriter[] subs;
                    lock (Server.Subs) { subs = Server.Subs.ToArray(); }
                    if (subs.Length > 0)
                    {
                        string json = Snapshot();
                        foreach (StreamWriter w in subs)
                            try { Server.Send(w, json); } catch (Exception) { }
                    }
                }
                catch (Exception) { }
                Thread.Sleep(1000);
            }
        }
    }

    // Finds ETS2 through Steam and installs the telemetry plugin from the official GitHub release.
    static class PluginInstaller
    {
        const string ReleaseUrl = "https://github.com/RenCloud/scs-sdk-plugin/releases/download/V.1.12.1/release_v_1_12_1.zip";

        static List<string> SteamLibraries()
        {
            var roots = new List<string>();
            try { using (RegistryKey k = Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam")) { if (k != null && k.GetValue("SteamPath") != null) roots.Add(k.GetValue("SteamPath").ToString().Replace('/', '\\')); } } catch (Exception) { }
            try { using (RegistryKey k = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\WOW6432Node\Valve\Steam")) { if (k != null && k.GetValue("InstallPath") != null) roots.Add(k.GetValue("InstallPath").ToString()); } } catch (Exception) { }
            var libs = new List<string>(roots);
            foreach (string r in roots)
            {
                string vdf = Path.Combine(r, @"steamapps\libraryfolders.vdf");
                if (!File.Exists(vdf)) continue;
                foreach (string line in File.ReadAllLines(vdf))
                {
                    string t = line.Trim();
                    if (!t.StartsWith("\"path\"")) continue;
                    string[] parts = t.Split('"');          // "path"  "D:\\SteamLibrary"
                    if (parts.Length >= 4) libs.Add(parts[3].Replace("\\\\", "\\"));
                }
            }
            return libs;
        }

        public static string FindGameDir()
        {
            foreach (string lib in SteamLibraries())
            {
                string g = Path.Combine(lib, @"steamapps\common\Euro Truck Simulator 2");
                if (File.Exists(Path.Combine(g, @"bin\win_x64\eurotrucks2.exe"))) return g;
            }
            return null;
        }

        public static string PluginDir(string gameDir) { return Path.Combine(gameDir, @"bin\win_x64\plugins"); }

        public static bool IsInstalled()
        {
            string g = FindGameDir();
            if (g == null) return false;
            string p = PluginDir(g);
            return Directory.Exists(p) && Directory.GetFiles(p, "scs-telemetry*.dll").Length > 0;
        }

        // Returns a message for the user. ok=true when the dll is in place.
        public static string Install(out bool ok)
        {
            ok = false;
            string game = FindGameDir();
            if (game == null)
            {
                using (FolderBrowserDialog fb = new FolderBrowserDialog())
                {
                    fb.Description = "ETS2 klasörü otomatik bulunamadı. 'Euro Truck Simulator 2' klasörünü seç.";
                    if (fb.ShowDialog() != DialogResult.OK) return "Kurulum iptal edildi.";
                    game = fb.SelectedPath;
                }
                if (!File.Exists(Path.Combine(game, @"bin\win_x64\eurotrucks2.exe")))
                    return "Seçilen klasörde bin\\win_x64\\eurotrucks2.exe yok. Doğru klasörü seç.";
            }
            string zip = Path.Combine(Path.GetTempPath(), "krankmili-scs-plugin.zip");
            try
            {
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072; // TLS 1.2
                using (WebClient wc = new WebClient()) { wc.DownloadFile(ReleaseUrl, zip); }
                string dir = PluginDir(game);
                Directory.CreateDirectory(dir);
                using (ZipArchive za = ZipFile.OpenRead(zip))
                {
                    ZipArchiveEntry hit = null; int best = -100;
                    var all = new StringBuilder();
                    foreach (ZipArchiveEntry e in za.Entries)
                    {
                        all.Append(e.FullName).Append("; ");
                        string n = e.FullName.Replace('\\', '/').ToLowerInvariant();
                        if (!n.EndsWith(".dll")) continue;
                        int score = 0;
                        if (n.Contains("x64") || n.Contains("win64")) score += 3;
                        else if (n.Contains("64")) score += 2;
                        if (n.Contains("x86") || n.Contains("win32")) score -= 5;
                        if (n.Contains("telemetry")) score += 1;
                        if (score > best) { best = score; hit = e; }
                    }
                    Log.Info("zip entries: {0}", all.ToString());
                    if (hit == null || best < 1)
                        return "İndirilen pakette 64-bit telemetri dll'i bulunamadı.\nPaket içeriği: " + all.ToString();
                    string target = Path.Combine(dir, hit.Name);
                    hit.ExtractToFile(target, true);
                    Log.Info("plugin installed: {0}", target);
                }
                ok = true;
                Telemetry.PluginInstalled = true;
                return "Telemetri eklentisi kuruldu:\n" + PluginDir(game) + "\n\nETS2 açıksa kapatıp yeniden başlat. Oyun ilk açılışta eklenti için onay isteyebilir, 'Evet/OK' de.";
            }
            catch (Exception ex)
            {
                Log.Info("plugin install failed: {0}", ex.ToString());
                return "Kurulum başarısız: " + ex.Message;
            }
            finally { try { File.Delete(zip); } catch (Exception) { } }
        }
    }

    // ---------------------------------------------------------------- tray app
    static class Program
    {
        static NotifyIcon tray; static Server server; static string dir;

        static string ReadEmbeddedConfig()
        {
            using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream("config.json"))
            using (StreamReader r = new StreamReader(s, Encoding.UTF8)) return r.ReadToEnd();
        }

        static List<string> LocalIps()
        {
            var l = new List<string>();
            foreach (IPAddress a in Dns.GetHostAddresses(Dns.GetHostName()))
                if (a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a)) l.Add(a.ToString());
            return l;
        }

        static Icon MakeIcon()
        {
            Bitmap bmp = new Bitmap(32, 32);
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAlias;
                g.FillEllipse(new SolidBrush(Color.FromArgb(255, 176, 32)), 1, 1, 30, 30);
                using (Font f = new Font("Segoe UI", 16, FontStyle.Bold, GraphicsUnit.Pixel))
                {
                    StringFormat sf = new StringFormat(); sf.Alignment = StringAlignment.Center; sf.LineAlignment = StringAlignment.Center;
                    g.DrawString("K", f, new SolidBrush(Color.FromArgb(14, 16, 19)), new RectangleF(0, 1, 32, 32), sf);
                }
            }
            return Icon.FromHandle(bmp.GetHicon());
        }

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        static bool AutoStart()
        {
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey)) return k != null && k.GetValue("KrankmiliButtonBox") != null;
        }
        static void SetAutoStart(bool on)
        {
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
            {
                if (on) k.SetValue("KrankmiliButtonBox", "\"" + Application.ExecutablePath + "\"");
                else k.DeleteValue("KrankmiliButtonBox", false);
            }
        }

        [STAThread]
        static void Main(string[] args)
        {
            dir = AppDomain.CurrentDomain.BaseDirectory;
            Log.Path = Path.Combine(dir, "krankmili-server.log");
            bool created;
            Mutex mutex = new Mutex(true, "Krankmili.ButtonBox.Server", out created);
            Application.EnableVisualStyles();
            if (!created) { MessageBox.Show("Server zaten çalışıyor (sağ alttaki tray ikonuna bak).", Const.AppName); return; }

            string cfgPath = Path.Combine(dir, "config.json");
            if (!File.Exists(cfgPath)) File.WriteAllText(cfgPath, ReadEmbeddedConfig(), new UTF8Encoding(false));

            Config cfg;
            try { cfg = Config.Load(File.ReadAllText(cfgPath, Encoding.UTF8)); }
            catch (Exception ex)
            {
                MessageBox.Show("config.json hatalı:\n" + ex.Message + "\n\nDosya: " + cfgPath, Const.AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            KeyState keys = new KeyState();
            foreach (string a in args) if (a == "--dry-run") keys.DryRun = true;
            server = new Server(cfg, keys);
            try { server.Start(); }
            catch (Exception ex)
            {
                MessageBox.Show("Port " + cfg.Port + " açılamadı (başka program kullanıyor olabilir):\n" + ex.Message, Const.AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            Telemetry.PluginInstalled = PluginInstaller.IsInstalled();
            Thread tt = new Thread(Telemetry.Loop); tt.IsBackground = true; tt.Start();

            List<string> ips = LocalIps();
            string ipText = ips.Count > 0 ? string.Join(", ", ips.ToArray()) : "IP bulunamadı";
            Log.Info("listening on port {0}; IPs: {1}", cfg.Port, ipText);
            Log.Info("server elevated: {0}; ETS2 state: {1} (0=off,1=normal,2=admin)", Diag.SelfElevated(), Diag.Ets2State());

            tray = new NotifyIcon();
            tray.Icon = MakeIcon();
            tray.Visible = true;
            Action refreshText = delegate
            {
                string t = Const.AppName + " — " + server.Clients + " tablet bağlı";
                tray.Text = t.Length > 63 ? t.Substring(0, 63) : t;
            };
            ContextMenuStrip menu = new ContextMenuStrip();
            bool ipHidden = false;
            Func<string> ipLine = delegate { return ipHidden ? "IP: •••.•••.•••.•••   Port: •••• (gizli)" : "IP: " + ipText + "   Port: " + cfg.Port; };
            ToolStripItem info = menu.Items.Add(ipLine()); info.Enabled = false;
            // Eye toggle: hides the address in the menu, the double-click balloon and the log-free UI (stream-safe).
            ToolStripMenuItem eye = new ToolStripMenuItem("\uD83D\uDC41  IP'yi göster / gizle");
            eye.Click += delegate { ipHidden = !ipHidden; info.Text = ipLine(); eye.Text = (ipHidden ? "\uD83D\uDE48  IP'yi göster" : "\uD83D\uDC41  IP'yi gizle"); };
            eye.Text = "\uD83D\uDC41  IP'yi gizle";
            menu.Items.Add(eye);
            ToolStripItem warn = null;
            if (cfg.Token.Length == 0) { warn = menu.Items.Add("Token yok: ağdaki herkes tuş gönderebilir"); warn.Enabled = false; }
            menu.Items.Add(new ToolStripSeparator());
            ToolStripMenuItem auto = new ToolStripMenuItem("Windows ile başlat"); auto.Checked = AutoStart(); auto.CheckOnClick = true;
            auto.CheckedChanged += delegate { try { SetAutoStart(auto.Checked); } catch (Exception) { } };
            menu.Items.Add(auto);
            menu.Items.Add("Telemetri eklentisini kur / onar", null, delegate
            {
                bool ok; string msg = PluginInstaller.Install(out ok);
                MessageBox.Show(msg, Const.AppName, MessageBoxButtons.OK, ok ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
            });
            if (!Diag.SelfElevated())
                menu.Items.Add("Yönetici olarak yeniden başlat", null, delegate
                {
                    try
                    {
                        ProcessStartInfo psi = new ProcessStartInfo(Application.ExecutablePath); psi.UseShellExecute = true; psi.Verb = "runas";
                        Process.Start(psi);
                        tray.Visible = false; server.Stop(); Application.Exit();
                    }
                    catch (Exception ex) { Log.Info("elevation cancelled: {0}", ex.Message); }
                });
            menu.Items.Add("Tanılama (tuşlar neden çalışmıyor?)", null, delegate { MessageBox.Show(Diag.Report(cfg, server.Clients), Const.AppName, MessageBoxButtons.OK, MessageBoxIcon.Information); });
            menu.Items.Add("Ayarları varsayılana sıfırla (yedek alır, kapanır)", null, delegate
            {
                if (MessageBox.Show("config.json yedeklenip yeni varsayılan düzen yazılacak ve sunucu kapanacak. Sonra sunucuyu yeniden aç.\n\nDevam?", Const.AppName, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
                try
                {
                    if (File.Exists(cfgPath)) File.Copy(cfgPath, Path.Combine(dir, "config.yedek-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".json"), true);
                    File.WriteAllText(cfgPath, ReadEmbeddedConfig(), new UTF8Encoding(false));
                    tray.Visible = false; server.Stop(); Application.Exit();
                }
                catch (Exception ex) { MessageBox.Show("Sıfırlanamadı: " + ex.Message, Const.AppName); }
            });
            menu.Items.Add("config.json'u aç", null, delegate { Process.Start("notepad.exe", "\"" + cfgPath + "\""); });
            menu.Items.Add("Kayıt dosyasını aç", null, delegate { if (File.Exists(Log.Path)) Process.Start("notepad.exe", "\"" + Log.Path + "\""); });
            menu.Items.Add(new ToolStripSeparator());
            // Update notice: checked once at start (can be switched off; nothing else is ever sent to GitHub).
            ToolStripMenuItem updItem = new ToolStripMenuItem("Yeni sürüm var"); updItem.Visible = false;
            string updUrl = "";
            updItem.Click += delegate { try { if (updUrl.StartsWith("https://")) Process.Start(updUrl); } catch (Exception) { } };
            menu.Items.Add(updItem);
            ToolStripMenuItem updCheck = new ToolStripMenuItem("Açılışta yeni sürümü kontrol et (GitHub'a bağlanır)");
            updCheck.Checked = !File.Exists(UpdateCheck.OffFile(dir)); updCheck.CheckOnClick = true;
            updCheck.CheckedChanged += delegate
            {
                try
                {
                    if (updCheck.Checked) File.Delete(UpdateCheck.OffFile(dir));
                    else File.WriteAllText(UpdateCheck.OffFile(dir), "update check disabled");
                }
                catch (Exception) { }
            };
            menu.Items.Add(updCheck);
            if (updCheck.Checked)
            {
                string foundV = null, foundN = "";
                System.Windows.Forms.Timer ut2 = new System.Windows.Forms.Timer(); ut2.Interval = 1000;
                ut2.Tick += delegate
                {
                    if (foundV == null) return;
                    ut2.Stop();
                    updItem.Text = "Yeni sürüm var: " + foundV + " (indir)"; updItem.Visible = true;
                    tray.ShowBalloonTip(8000, Const.AppName, "Yeni sürüm: " + foundV + (foundN.Length > 0 ? "\n" + foundN : "") + "\nİndirmek için tepsi menüsüne bak.", ToolTipIcon.Info);
                };
                ut2.Start();
                Thread ut = new Thread(delegate ()
                {
                    string v, u, n;
                    if (!UpdateCheck.Fetch(out v, out u, out n)) return;
                    updUrl = u; foundN = n; foundV = v;   // picked up by the UI timer (the menu has no window handle yet)
                });
                ut.IsBackground = true; ut.Start();
            }
            menu.Items.Add("Çıkış", null, delegate { tray.Visible = false; server.Stop(); Application.Exit(); });
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += delegate { tray.ShowBalloonTip(5000, Const.AppName, ipHidden ? "IP gizli (menüden göster)" : "IP: " + ipText + "  Port: " + cfg.Port, ToolTipIcon.Info); };

            System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer(); timer.Interval = 2000;
            timer.Tick += delegate { refreshText(); }; timer.Start(); refreshText();

            // First run: offer to install the telemetry plugin (asked once).
            string choiceFile = Path.Combine(dir, "telemetry-choice.txt");
            if (!Telemetry.PluginInstalled && !File.Exists(choiceFile))
            {
                System.Windows.Forms.Timer once = new System.Windows.Forms.Timer(); once.Interval = 1500;
                once.Tick += delegate
                {
                    once.Stop();
                    DialogResult r = MessageBox.Show(
                        "Araç durumu (yakıt, hasar, yük) için ETS2 telemetri eklentisi gerekiyor.\n\nResmi eklenti (RenCloud scs-sdk-plugin, MIT) GitHub'dan indirilip ETS2 klasörüne kurulsun mu?",
                        Const.AppName, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                    try { File.WriteAllText(choiceFile, r == DialogResult.Yes ? "yes" : "no"); } catch (Exception) { }
                    if (r == DialogResult.Yes)
                    {
                        bool ok; string msg = PluginInstaller.Install(out ok);
                        MessageBox.Show(msg, Const.AppName, MessageBoxButtons.OK, ok ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
                    }
                };
                once.Start();
            }

            tray.ShowBalloonTip(6000, Const.AppName, "Server çalışıyor. Tabletten 'PC'yi otomatik bul'a bas.\nIP: " + ipText + "  Port: " + cfg.Port, ToolTipIcon.Info);
            Application.Run();
            tray.Dispose();
            GC.KeepAlive(mutex);
        }
    }
}
