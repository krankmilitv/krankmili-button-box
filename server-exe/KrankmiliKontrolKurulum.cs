// Krankmili Kontrol Kurulum: adds the button box keys to the game's controls.sii.
// Only ADDS "| keyboard.X?0" alternatives to the matching game actions. Nothing is removed or replaced,
// every file is backed up first, and a changed file is verified (same line count) before it stays.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

static class Program
{
    const string BackupTag = ".krankmili-yedek-";

    // button id -> game action (the "mix" name in controls.sii)
    static readonly Dictionary<string, string[]> Map = new Dictionary<string, string[]> {
        {"window_left_up", new[]{"lwinclose"}}, {"window_left_down", new[]{"lwinopen"}},
        {"window_right_up", new[]{"rwinclose"}}, {"window_right_down", new[]{"rwinopen"}},
        {"bus_door", new[]{"entrdoors"}}, {"engine_toggle", new[]{"engine"}},
        {"flash", new[]{"lighthorn"}}, {"high_beam", new[]{"hblight"}},
        {"light_mode", new[]{"light"}}, {"light_low_on", new[]{"light"}}, {"light_park_on", new[]{"light"}}, {"light_all_off", new[]{"light"}},
        {"indicator_l", new[]{"lblinker"}}, {"indicator_r", new[]{"rblinker"}}, {"hazard", new[]{"flasher4way"}},
        {"parking", new[]{"parkingbrake"}},
        {"trailer_attach", new[]{"attach"}}, {"trailer_detach", new[]{"attach"}},
        {"axle_lower", new[]{"liftaxle"}}, {"axle_raise", new[]{"liftaxle"}},
        {"trailer_axle_down_p1", new[]{"liftaxlet"}}, {"trailer_axle_up_p1", new[]{"liftaxlet"}},
        {"cam_1", new[]{"cam1"}}, {"cam_2", new[]{"cam2"}}, {"cam_3", new[]{"cam3"}},
        {"cam_4", new[]{"cam4"}}, {"cam_5", new[]{"cam5"}}, {"cam_6", new[]{"cam6"}},
        {"park_cam", new[]{"parking_cams"}},
        {"susp_front_up", new[]{"frontsuspup"}}, {"susp_front_down", new[]{"frontsuspdwn"}},
        {"susp_rear_up", new[]{"rearsuspup"}}, {"susp_rear_down", new[]{"rearsuspdwn"}},
        {"susp_trailer_up", new[]{"trlrsuspup"}}, {"susp_trailer_down", new[]{"trlrsuspdwn"}},
        {"susp_reset", new[]{"suspreset"}},
        {"cabin_light", new[]{"cabinlight"}}, {"beacon", new[]{"beacon"}},
        {"wipers", new[]{"wipers"}}, {"wipers_on", new[]{"wipers"}}, {"wipers_off", new[]{"wipers"}},
        {"sunvisor_left_down", new[]{"winlshadedwn"}}, {"sunvisor_left_up", new[]{"winlshadeup"}},
        {"sunvisor_right_down", new[]{"winrshadedwn"}}, {"sunvisor_right_up", new[]{"winrshadeup"}},
    };

    // button box key name -> controls.sii key name. Names marked "guess" are only used if the file already contains them.
    static readonly Dictionary<string, string> Names = new Dictionary<string, string> {
        {"space","space"},{"enter","enter"},{"esc","esc"},{"tab","tab"},{"insert","ins"},{"ins","ins"},{"delete","del"},{"del","del"},
        {"pageup","pgup"},{"pgup","pgup"},{"pagedown","pgdn"},{"pgdn","pgdn"},{"home","home"},{"end","end"},
        {"up","uarrow"},{"down","darrow"},{"left","larrow"},{"right","rarrow"},
        {"[","lbracket"},{"]","rbracket"},{";","semicolon"},{"'","apostrophe"},{"`","grave"},{"=","equal"},
        {"-","minus"},{",","comma"},{".","period"},{"/","slash"},{"\\","bslash"},
        {"num*","nummultiply"},{"num+","numplus"},{"num-","numminus"},{"num.","numperiod"},{"num/","numslash"},
        {"numenter","numenter"},{"numlock","numlock"},
    };
    static readonly HashSet<string> Guess = new HashSet<string> { "minus", "comma", "period", "slash", "bslash" };

    static bool yes, dry, force;

    static int Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        yes = args.Contains("--yes"); dry = args.Contains("--dry"); force = args.Contains("--force");
        Console.WriteLine("Krankmili Kontrol Kurulum");
        Console.WriteLine("Button box tuşlarını oyunun kontrol ayarlarına ekler. Mevcut ayarların hiçbiri silinmez.");
        Console.WriteLine();
        try
        {
            if (!force && !dry && GameRunning())
            {
                Console.WriteLine("Oyun açık. Oyunu kapat ve bu programı tekrar çalıştır (oyun kapanırken ayarları geri yazar).");
                return Done(1);
            }
            List<string> files = FindControlFiles(args);
            if (files.Count == 0)
            {
                Console.WriteLine("controls.sii bulunamadı. Oyunu bir kez açıp bir profil oluşturduktan sonra tekrar dene.");
                Console.WriteLine("Elle vermek için: KrankmiliKontrolKurulum.exe --dosya \"C:\\...\\controls.sii\"");
                return Done(1);
            }
            if (args.Contains("--geri-al")) return Undo(files);

            List<Item> items = LoadItems(args);
            Console.WriteLine("{0} profil bulundu, {1} button box tuşu işlenecek.", files.Count, items.Count);
            var plans = new List<Plan>();
            foreach (string f in files)
            {
                Plan p = MakePlan(f, items);
                plans.Add(p);
                Console.WriteLine();
                Console.WriteLine("Profil: {0}", p.Title);
                if (p.Adds.Count == 0) Console.WriteLine("  Eklenecek bir şey yok (zaten hazır).");
                foreach (string a in p.Adds) Console.WriteLine("  + " + a);
                foreach (string s in p.Skips) Console.WriteLine("  - atlandı: " + s);
            }
            int total = plans.Sum(p => p.Adds.Count);
            Console.WriteLine();
            if (total == 0) { Console.WriteLine("Her şey zaten ayarlı."); return Done(0); }
            if (dry) { Console.WriteLine("Deneme modu: dosyalara dokunulmadı."); return Done(0); }
            if (!yes)
            {
                Console.Write("Bu eklemeler yapılsın mı? Önce yedek alınır. (E/H): ");
                string a = Console.ReadLine();
                if (a == null || !a.Trim().ToLowerInvariant().StartsWith("e")) { Console.WriteLine("İptal edildi."); return Done(0); }
            }
            foreach (Plan p in plans) if (p.Adds.Count > 0) Apply(p);
            Console.WriteLine();
            Console.WriteLine("Bitti. Oyunu aç; button box tuşları kontrollerde görünür. Geri almak için: KrankmiliKontrolKurulum.exe --geri-al");
            return Done(0);
        }
        catch (Exception ex)
        {
            Console.WriteLine("Hata: " + ex.Message);
            return Done(2);
        }
    }

    static int Done(int code)
    {
        if (!yes) { Console.WriteLine(); Console.WriteLine("Kapatmak için bir tuşa bas..."); try { Console.ReadKey(true); } catch (Exception) { } }
        return code;
    }

    static bool GameRunning()
    {
        foreach (string n in new[] { "eurotrucks2", "amtrucks" })
            if (Process.GetProcessesByName(n).Length > 0) return true;
        return false;
    }

    static List<string> FindControlFiles(string[] args)
    {
        var list = new List<string>();
        int i = Array.IndexOf(args, "--dosya");
        if (i >= 0 && i + 1 < args.Length) { if (File.Exists(args[i + 1])) list.Add(args[i + 1]); return list; }
        string docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        var games = new List<string> { "Euro Truck Simulator 2" };
        if (args.Contains("--ats")) games.Add("American Truck Simulator");
        foreach (string g in games)
            foreach (string sub in new[] { "profiles", "steam_profiles" })
            {
                string dir = Path.Combine(Path.Combine(docs, g), sub);
                if (!Directory.Exists(dir)) continue;
                foreach (string p in Directory.GetDirectories(dir))
                {
                    string c = Path.Combine(p, "controls.sii");
                    if (File.Exists(c)) list.Add(c);
                }
            }
        return list;
    }

    class Item { public string Id, Label, Spec; }

    static List<Item> LoadItems(string[] args)
    {
        string json = null;
        int i = Array.IndexOf(args, "--config");
        string ext = (i >= 0 && i + 1 < args.Length) ? args[i + 1] : Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "config.json");
        if (File.Exists(ext)) json = File.ReadAllText(ext, Encoding.UTF8);
        else
            using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream("config.json"))
            using (var r = new StreamReader(s, Encoding.UTF8)) json = r.ReadToEnd();
        var ser = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
        var root = ser.Deserialize<Dictionary<string, object>>(json);
        var items = new List<Item>();
        var seen = new HashSet<string>();
        foreach (object go in (System.Collections.IEnumerable)root["groups"])
        {
            var g = (Dictionary<string, object>)go;
            if (!g.ContainsKey("buttons")) continue;
            foreach (object bo in (System.Collections.IEnumerable)g["buttons"])
            {
                var b = (Dictionary<string, object>)bo;
                string id = Convert.ToString(b["id"]);
                if (!Map.ContainsKey(id) || !b.ContainsKey("key") || !seen.Add(id)) continue;
                items.Add(new Item { Id = id, Label = b.ContainsKey("sub") ? Convert.ToString(b["sub"]) + " " + Convert.ToString(b["label"]) : Convert.ToString(b["label"]), Spec = Convert.ToString(b["key"]) });
            }
        }
        return items;
    }

    class Plan { public string File, Title, Text; public List<string> Adds = new List<string>(), Skips = new List<string>(); public Dictionary<string, List<string>> ByMix = new Dictionary<string, List<string>>(); }

    static string ProfileTitle(string file)
    {
        string dir = Path.GetFileName(Path.GetDirectoryName(file));
        string kind = Path.GetFileName(Path.GetDirectoryName(Path.GetDirectoryName(file)));
        string name = dir;
        try
        {
            if (dir.Length % 2 == 0 && Regex.IsMatch(dir, "^[0-9A-Fa-f]+$"))
            {
                var bytes = new byte[dir.Length / 2];
                for (int i = 0; i < bytes.Length; i++) bytes[i] = Convert.ToByte(dir.Substring(i * 2, 2), 16);
                name = Encoding.UTF8.GetString(bytes);
            }
        }
        catch (Exception) { }
        return name + "  (" + kind + ")";
    }

    // key spec like "f8", "shift+f8", "num7" -> controls.sii expression; null when the name is not known to be valid
    static string Expr(string spec, HashSet<string> known, HashSet<string> modKeys, out string why)
    {
        why = null;
        string s = spec.ToLowerInvariant().Replace("num+", "numplus");
        var parts = s.Split('+').Select(x => x.Trim() == "numplus" ? "num+" : x.Trim()).ToList();
        string key = parts[parts.Count - 1];
        var mods = parts.Take(parts.Count - 1).Select(m => m == "lctrl" ? "ctrl" : m == "lshift" ? "shift" : m == "lalt" ? "alt" : m).OrderBy(m => m).ToList();
        string name;
        if (Regex.IsMatch(key, "^[a-z]$")) name = key;
        else if (Regex.IsMatch(key, "^[0-9]$")) name = "key" + key;
        else if (Regex.IsMatch(key, "^f([1-9]|1[0-2])$")) name = key;
        else if (Regex.IsMatch(key, "^num[0-9]$")) name = key;
        else if (!Names.TryGetValue(key, out name)) { why = "bilinmeyen tuş '" + key + "'"; return null; }
        if (Guess.Contains(name) && !known.Contains(name)) { why = "'" + key + "' tuşunun oyundaki adı doğrulanamadı"; return null; }
        string kb = "keyboard." + name + "?0";
        if (mods.Count == 0) return modKeys.Contains(name) ? "modifier(no_modifier, " + kb + ")" : kb;
        string m2 = string.Join("+", mods.ToArray());
        string mod = m2 == "ctrl" ? "ctrl_only" : m2 == "shift" ? "shift_only" : m2 == "alt" ? "alt_only" : m2 == "ctrl+shift" ? "ctr_shf" : null;
        if (mod == null) { why = "desteklenmeyen tuş birleşimi '" + spec + "'"; return null; }
        return "modifier(" + mod + ", " + kb + ")";
    }

    static readonly Regex Line = new Regex("(?m)^( config_lines\\[\\d+\\]: \"mix )([a-z0-9_]+) `([^`\\r\\n]*)`\"", RegexOptions.Compiled);

    static Plan MakePlan(string file, List<Item> items)
    {
        var p = new Plan { File = file, Title = ProfileTitle(file), Text = File.ReadAllText(file, new UTF8Encoding(false)) };
        var known = new HashSet<string>(Regex.Matches(p.Text, "keyboard\\.([a-z0-9_]+)").Cast<Match>().Select(m => m.Groups[1].Value));
        var mixes = new Dictionary<string, string>();
        foreach (Match m in Line.Matches(p.Text)) mixes[m.Groups[2].Value] = m.Groups[3].Value;

        // keys that also appear with a modifier in our config: the plain use must say "no_modifier"
        var modKeys = new HashSet<string>();
        foreach (Item it in items)
        {
            string sp = it.Spec.ToLowerInvariant().Replace("num+", "numplus");
            if (sp.Length > 1 && sp.Contains("+"))
            {
                string w; string e = Expr(it.Spec, known, new HashSet<string>(), out w);
                if (e != null) modKeys.Add(Regex.Match(e, "keyboard\\.([a-z0-9_]+)").Groups[1].Value);
            }
        }

        foreach (Item it in items)
        {
            string why;
            string expr = Expr(it.Spec, known, modKeys, out why);
            if (expr == null) { p.Skips.Add(it.Label + ": " + why); continue; }
            foreach (string mix in Map[it.Id])
            {
                string cur;
                if (!mixes.TryGetValue(mix, out cur)) { p.Skips.Add(it.Label + ": oyunda '" + mix + "' eylemi yok"); continue; }
                bool has = cur.Split('|').Select(t => t.Trim()).Any(t => string.Equals(t, expr, StringComparison.OrdinalIgnoreCase));
                List<string> list;
                if (!p.ByMix.TryGetValue(mix, out list)) p.ByMix[mix] = list = new List<string>();
                if (has || list.Contains(expr)) continue;
                list.Add(expr);
                p.Adds.Add(it.Label + "  [" + it.Spec + "]  ->  oyun eylemi '" + mix + "'");
            }
        }
        return p;
    }

    static void Apply(Plan p)
    {
        string backup = p.File + BackupTag + DateTime.Now.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture);
        File.Copy(p.File, backup, false);
        string updated = Line.Replace(p.Text, m =>
        {
            List<string> add;
            if (!p.ByMix.TryGetValue(m.Groups[2].Value, out add) || add.Count == 0) return m.Value;
            string inner = m.Groups[3].Value;
            foreach (string e in add) inner = inner.Length == 0 ? e : inner + " | " + e;
            return m.Groups[1].Value + m.Groups[2].Value + " `" + inner + "`\"";
        });
        // sanity: same number of config lines, same header count
        int before = Regex.Matches(p.Text, "config_lines\\[").Count, after = Regex.Matches(updated, "config_lines\\[").Count;
        if (before != after || p.Text.Split('\n').Length != updated.Split('\n').Length)
        {
            Console.WriteLine("Doğrulama başarısız, {0} değiştirilmedi.", p.Title);
            File.Delete(backup);
            return;
        }
        File.WriteAllText(p.File, updated, new UTF8Encoding(false));
        Console.WriteLine("Tamam: {0}  (yedek: {1})", p.Title, Path.GetFileName(backup));
    }

    static int Undo(List<string> files)
    {
        Console.WriteLine("Geri alma: her profilde en son yedek geri yüklenir. O günden sonra oyunda yaptığın kontrol değişiklikleri de geri gider.");
        if (!yes) { Console.Write("Devam edilsin mi? (E/H): "); string a = Console.ReadLine(); if (a == null || !a.Trim().ToLowerInvariant().StartsWith("e")) return Done(0); }
        foreach (string f in files)
        {
            string dir = Path.GetDirectoryName(f);
            string last = Directory.GetFiles(dir, "controls.sii" + BackupTag + "*").OrderBy(x => x).LastOrDefault();
            if (last == null) { Console.WriteLine("Yedek yok: " + ProfileTitle(f)); continue; }
            File.Copy(last, f, true);
            Console.WriteLine("Geri yüklendi: {0}  ({1})", ProfileTitle(f), Path.GetFileName(last));
        }
        return Done(0);
    }
}
