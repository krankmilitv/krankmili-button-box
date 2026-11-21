// Krankmili telemetry plugin: writes the active bus job (ETS2 Coaches DLC) to a JSON file.
// Freestanding build (no CRT). Output: %TEMP%\krankmili_bus.json, replaced atomically.
#include "scssdk_telemetry.h"
#include "eurotrucks2/scssdk_telemetry_eut2.h"
#include "common/scssdk_telemetry_common_configs.h"
#include "common/scssdk_telemetry_common_channels.h"

extern "C" {
typedef void *HANDLE;
__declspec(dllimport) HANDLE __stdcall CreateFileA(const char *, unsigned long, unsigned long, void *, unsigned long, unsigned long, HANDLE);
__declspec(dllimport) int __stdcall WriteFile(HANDLE, const void *, unsigned long, unsigned long *, void *);
__declspec(dllimport) int __stdcall CloseHandle(HANDLE);
__declspec(dllimport) unsigned long __stdcall GetTempPathA(unsigned long, char *);
__declspec(dllimport) int __stdcall MoveFileExA(const char *, const char *, unsigned long);
}

extern "C" { int _fltused = 0; }
namespace {
const unsigned BUF_SIZE = 60000;
char g_attrs[BUF_SIZE];      // JSON members of the current bus_job config ("name":value,...)
unsigned g_attrs_len = 0;
char g_event[1024];          // last gameplay event (JSON object text)
unsigned g_event_len = 0;
char g_out[BUF_SIZE + 4096];
char g_path[300], g_tmp[300];
bool g_active = false;
unsigned g_seq = 0;
scs_u32_t g_game_time = 0;
scs_timestamp_t g_last_write = 0;
bool g_dirty = false;
scs_log_t g_log = nullptr;

struct W { char *p; unsigned n, cap; };

void put(W &w, char c) { if (w.n + 1 < w.cap) w.p[w.n++] = c; }
void puts_(W &w, const char *s) { while (*s) put(w, *s++); }
void put_u64(W &w, unsigned long long v) {
    char t[24]; int i = 0;
    if (!v) t[i++] = '0';
    while (v) { t[i++] = (char)('0' + v % 10); v /= 10; }
    while (i) put(w, t[--i]);
}
void put_s64(W &w, long long v) {
    if (v < 0) { put(w, '-'); put_u64(w, (unsigned long long)(-v)); } else put_u64(w, (unsigned long long)v);
}
void put_double(W &w, double v) {
    if (v != v) { puts_(w, "null"); return; }
    if (v < 0) { put(w, '-'); v = -v; }
    if (v > 9e15) { puts_(w, "0"); return; }
    unsigned long long ip = (unsigned long long)v;
    double fp = v - (double)ip;
    unsigned long long f = (unsigned long long)(fp * 1000.0 + 0.5);
    if (f >= 1000) { ip++; f -= 1000; }
    put_u64(w, ip);
    put(w, '.');
    put(w, (char)('0' + f / 100)); put(w, (char)('0' + (f / 10) % 10)); put(w, (char)('0' + f % 10));
}
void put_str(W &w, const char *s) {
    put(w, '"');
    for (; s && *s; ++s) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') { put(w, '\\'); put(w, (char)c); }
        else if (c < 0x20) { put(w, ' '); }
        else put(w, (char)c);
    }
    put(w, '"');
}

// Serialises named attributes as JSON members: "name":value or "name[idx]":value
void put_attrs(W &w, const scs_named_value_t *a) {
    bool first = true;
    for (; a && a->name; ++a) {
        if (a->value.type == SCS_VALUE_TYPE_INVALID) continue;
        if (!first) put(w, ',');
        first = false;
        char key[160]; W k = { key, 0, sizeof(key) - 1 };
        puts_(k, a->name);
        if (a->index != SCS_U32_NIL) { put(k, '['); put_u64(k, a->index); put(k, ']'); }
        key[k.n] = 0;
        put_str(w, key);
        put(w, ':');
        switch (a->value.type) {
            case SCS_VALUE_TYPE_bool: puts_(w, a->value.value_bool.value ? "true" : "false"); break;
            case SCS_VALUE_TYPE_s32: put_s64(w, a->value.value_s32.value); break;
            case SCS_VALUE_TYPE_u32: put_u64(w, a->value.value_u32.value); break;
            case SCS_VALUE_TYPE_s64: put_s64(w, a->value.value_s64.value); break;
            case SCS_VALUE_TYPE_u64: put_u64(w, a->value.value_u64.value); break;
            case SCS_VALUE_TYPE_float: put_double(w, a->value.value_float.value); break;
            case SCS_VALUE_TYPE_double: put_double(w, a->value.value_double.value); break;
            case SCS_VALUE_TYPE_string: put_str(w, a->value.value_string.value); break;
            default: puts_(w, "null"); break;
        }
    }
}

bool str_eq(const char *a, const char *b) {
    while (*a && *a == *b) { ++a; ++b; }
    return *a == *b;
}

void flush_file() {
    if (!g_path[0]) return;
    W w = { g_out, 0, sizeof(g_out) - 1 };
    puts_(w, "{\"v\":1,\"seq\":"); put_u64(w, ++g_seq);
    puts_(w, ",\"active\":"); puts_(w, g_active ? "true" : "false");
    puts_(w, ",\"gameTime\":"); put_u64(w, g_game_time);
    puts_(w, ",\"job\":{");
    for (unsigned i = 0; i < g_attrs_len; ++i) put(w, g_attrs[i]);
    puts_(w, "},\"event\":");
    if (g_event_len) { for (unsigned i = 0; i < g_event_len; ++i) put(w, g_event[i]); } else puts_(w, "null");
    puts_(w, "}\n");
    HANDLE h = CreateFileA(g_tmp, 0x40000000 /*GENERIC_WRITE*/, 1 /*FILE_SHARE_READ*/, nullptr, 2 /*CREATE_ALWAYS*/, 0x80, nullptr);
    if ((long long)h == -1) return;
    unsigned long wrote = 0;
    WriteFile(h, g_out, w.n, &wrote, nullptr);
    CloseHandle(h);
    MoveFileExA(g_tmp, g_path, 1 /*REPLACE_EXISTING*/);
    g_dirty = false;
}

SCSAPI_VOID on_config(const scs_event_t, const void *const info, const scs_context_t) {
    const scs_telemetry_configuration_t *c = static_cast<const scs_telemetry_configuration_t *>(info);
    if (!str_eq(c->id, SCS_TELEMETRY_CONFIG_bus_job)) return;
    W w = { g_attrs, 0, BUF_SIZE - 1 };
    put_attrs(w, c->attributes);
    g_attrs_len = w.n;
    if (w.n > 0 && !g_active) g_event_len = 0;   // a new job: forget the previous job's end event
    g_active = w.n > 0;
    flush_file();
}

SCSAPI_VOID on_gameplay(const scs_event_t, const void *const info, const scs_context_t) {
    const scs_telemetry_gameplay_event_t *e = static_cast<const scs_telemetry_gameplay_event_t *>(info);
    W w = { g_event, 0, sizeof(g_event) - 1 };
    puts_(w, "{\"id\":"); put_str(w, e->id); puts_(w, ",\"a\":{");
    put_attrs(w, e->attributes);
    puts_(w, "}}");
    g_event_len = w.n;
    // a delivered / cancelled job clears the bus job
    if (str_eq(e->id, "job.delivered") || str_eq(e->id, "job.cancelled")) { /* freight events: ignore */ }
    flush_file();
}

SCSAPI_VOID on_frame_start(const scs_event_t, const void *const info, const scs_context_t) {
    const scs_telemetry_frame_start_t *f = static_cast<const scs_telemetry_frame_start_t *>(info);
    if (!g_active) return;
    // heartbeat once per second of game time (microseconds) so the reader sees gameTime move
    if (f->paused_simulation_time - g_last_write >= 1000000ull || f->paused_simulation_time < g_last_write) {
        g_last_write = f->paused_simulation_time;
        flush_file();
    }
}

SCSAPI_VOID on_game_time(const scs_string_t, const scs_u32_t, const scs_value_t *const v, const scs_context_t) {
    if (v && v->type == SCS_VALUE_TYPE_u32) g_game_time = v->value_u32.value;
}
}  // namespace

SCSAPI_RESULT scs_telemetry_init(const scs_u32_t version, const scs_telemetry_init_params_t *const params) {
    if (version != SCS_TELEMETRY_VERSION_1_01) return SCS_RESULT_unsupported;
    const scs_telemetry_init_params_v101_t *p = static_cast<const scs_telemetry_init_params_v101_t *>(params);
    g_log = p->common.log;

    unsigned long n = GetTempPathA(sizeof(g_path) - 40, g_path);
    if (n == 0 || n > sizeof(g_path) - 40) { g_path[0] = 0; if (g_log) g_log(SCS_LOG_TYPE_error, "[krankmili-bus] no temp path"); return SCS_RESULT_generic_error; }
    for (unsigned i = 0; i < n; ++i) g_tmp[i] = g_path[i];
    const char *a = "krankmili_bus.json"; const char *b = "krankmili_bus.tmp";
    unsigned i = 0; for (; a[i]; ++i) g_path[n + i] = a[i]; g_path[n + i] = 0;
    i = 0; for (; b[i]; ++i) g_tmp[n + i] = b[i]; g_tmp[n + i] = 0;

    g_active = false; g_attrs_len = 0; g_event_len = 0; g_game_time = 0; g_last_write = 0; g_seq = 0;

    bool ok = p->register_for_event(SCS_TELEMETRY_EVENT_configuration, on_config, nullptr) == SCS_RESULT_ok
           && p->register_for_event(SCS_TELEMETRY_EVENT_gameplay, on_gameplay, nullptr) == SCS_RESULT_ok
           && p->register_for_event(SCS_TELEMETRY_EVENT_frame_start, on_frame_start, nullptr) == SCS_RESULT_ok;
    if (!ok) { if (g_log) g_log(SCS_LOG_TYPE_error, "[krankmili-bus] cannot register events"); return SCS_RESULT_generic_error; }
    p->register_for_channel(SCS_TELEMETRY_CHANNEL_game_time, SCS_U32_NIL, SCS_VALUE_TYPE_u32, SCS_TELEMETRY_CHANNEL_FLAG_none, on_game_time, nullptr);
    flush_file();
    if (g_log) g_log(SCS_LOG_TYPE_message, "[krankmili-bus] ready: bus job goes to krankmili_bus.json in the temp folder");
    return SCS_RESULT_ok;
}

SCSAPI_VOID scs_telemetry_shutdown(void) {
    g_active = false; g_attrs_len = 0; g_event_len = 0;
    flush_file();
}
