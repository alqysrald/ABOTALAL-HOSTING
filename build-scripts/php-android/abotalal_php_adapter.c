#include "abotalal_php_adapter.h"

#include "php.h"
#include "php_main.h"
#include "php_variables.h"
#include "php_ini.h"
#include "SAPI.h"
#include "zend_extensions.h"
#include "zend_ini.h"
#include "ext/standard/php_standard.h"
#include "ext/standard/info.h"

#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

#ifdef __ANDROID__
#include <android/log.h>
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "AbotalalPhpSAPI", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AbotalalPhpSAPI", __VA_ARGS__)
#else
#define ALOGI(...) ((void)0)
#define ALOGE(...) ((void)0)
#endif

typedef struct DynBuffer {
    char *data;
    size_t len;
    size_t cap;
} DynBuffer;

static void dynbuf_init(DynBuffer *b) {
    b->data = (char *)malloc(4096);
    b->len = 0;
    b->cap = b->data ? 4096 : 0;
    if (b->data) b->data[0] = '\0';
}

static void dynbuf_append(DynBuffer *b, const char *src, size_t n) {
    if (!src || n == 0) return;
    if (b->len + n + 1 > b->cap) {
        size_t new_cap = b->cap == 0 ? 4096 : b->cap * 2;
        while (new_cap < b->len + n + 1) new_cap *= 2;
        char *next = (char *)realloc(b->data, new_cap);
        if (!next) return;
        b->data = next;
        b->cap = new_cap;
    }
    memcpy(b->data + b->len, src, n);
    b->len += n;
    b->data[b->len] = '\0';
}

static void dynbuf_free(DynBuffer *b) {
    free(b->data);
    b->data = NULL;
    b->len = 0;
    b->cap = 0;
}

typedef struct AbotalalSapiContext {
    const AbotalalPhpRequest *req;
    char *cookie_copy;
    size_t post_offset;
    DynBuffer out_headers;
    DynBuffer out_body;
    DynBuffer out_stderr;
    int http_status;
} AbotalalSapiContext;

static pthread_mutex_t g_php_mutex = PTHREAD_MUTEX_INITIALIZER;
static int g_engine_started = 0;
static char g_active_ini_path[1024] = {0};
static char *g_module_ini_override = NULL;
static AbotalalSapiContext *g_active_ctx = NULL;

static const char *DEFAULT_ANDROID_INI_ENTRIES =
    "html_errors=0\n"
    "register_argc_argv=0\n"
    "implicit_flush=0\n"
    "output_buffering=4096\n"
    "max_execution_time=60\n"
    "max_input_time=60\n"
    "memory_limit=256M\n"
    "file_uploads=1\n"
    "upload_max_filesize=64M\n"
    "post_max_size=64M\n"
    "variables_order=EGPCS\n"
    "request_order=GP\n"
    "cgi.fix_pathinfo=1\n\0";

static size_t abotalal_sapi_ub_write(const char *str, size_t str_length) {
    AbotalalSapiContext *ctx = g_active_ctx;
    if (ctx && str && str_length > 0) {
        dynbuf_append(&ctx->out_body, str, str_length);
    }
    return str_length;
}

static void abotalal_sapi_flush(void *server_context) {
    (void)server_context;
}

static int abotalal_sapi_header_handler(
    sapi_header_struct *sapi_header,
    sapi_header_op_enum op,
    sapi_headers_struct *sapi_headers
) {
    (void)sapi_header;
    (void)op;
    (void)sapi_headers;
    return SAPI_HEADER_ADD;
}

static int abotalal_sapi_send_headers(sapi_headers_struct *sapi_headers) {
    AbotalalSapiContext *ctx = g_active_ctx;
    if (!ctx || !sapi_headers) return SAPI_HEADER_SENT_SUCCESSFULLY;
    ctx->http_status = sapi_headers->http_response_code > 0
        ? sapi_headers->http_response_code
        : 200;

    zend_llist_position pos;
    sapi_header_struct *h = (sapi_header_struct *)zend_llist_get_first_ex(&sapi_headers->headers, &pos);
    while (h) {
        if (h->header && h->header_len > 0) {
            dynbuf_append(&ctx->out_headers, h->header, h->header_len);
            dynbuf_append(&ctx->out_headers, "\n", 1);
        }
        h = (sapi_header_struct *)zend_llist_get_next_ex(&sapi_headers->headers, &pos);
    }
    return SAPI_HEADER_SENT_SUCCESSFULLY;
}

static size_t abotalal_sapi_read_post(char *buffer, size_t count_bytes) {
    AbotalalSapiContext *ctx = g_active_ctx;
    if (!ctx || !ctx->req || !ctx->req->post_body || ctx->post_offset >= ctx->req->post_body_len) {
        return 0;
    }
    size_t remaining = ctx->req->post_body_len - ctx->post_offset;
    size_t to_read = count_bytes < remaining ? count_bytes : remaining;
    memcpy(buffer, ctx->req->post_body + ctx->post_offset, to_read);
    ctx->post_offset += to_read;
    return to_read;
}

static char *abotalal_sapi_read_cookies(void) {
    AbotalalSapiContext *ctx = g_active_ctx;
    if (!ctx) return NULL;
    return ctx->cookie_copy;
}

static void register_var_cstr(zval *track_vars, const char *name, const char *val) {
    if (!name || !val) return;
    php_register_variable_safe(name, val, strlen(val), track_vars);
}

static void abotalal_sapi_register_variables(zval *track_vars_array) {
    AbotalalSapiContext *ctx = g_active_ctx;
    if (!ctx || !ctx->req) return;
    const AbotalalPhpRequest *r = ctx->req;

    register_var_cstr(track_vars_array, "GATEWAY_INTERFACE", "CGI/1.1");
    register_var_cstr(track_vars_array, "SERVER_PROTOCOL", "HTTP/1.1");
    register_var_cstr(track_vars_array, "SERVER_SOFTWARE", "ABOTALAL-PHP-Embed/" PHP_VERSION);
    register_var_cstr(track_vars_array, "REQUEST_METHOD", r->method ? r->method : "GET");
    register_var_cstr(track_vars_array, "REQUEST_URI", r->request_uri ? r->request_uri : "/");
    register_var_cstr(track_vars_array, "QUERY_STRING", r->query_string ? r->query_string : "");
    register_var_cstr(track_vars_array, "SCRIPT_FILENAME", r->script_path ? r->script_path : "");
    register_var_cstr(track_vars_array, "SCRIPT_NAME", r->script_name ? r->script_name : "/index.php");
    register_var_cstr(track_vars_array, "PHP_SELF", r->script_name ? r->script_name : "/index.php");
    register_var_cstr(track_vars_array, "DOCUMENT_ROOT", r->document_root ? r->document_root : "");
    register_var_cstr(track_vars_array, "HTTP_HOST", r->http_host ? r->http_host : "127.0.0.1");
    register_var_cstr(track_vars_array, "SERVER_NAME", r->http_host ? r->http_host : "127.0.0.1");
    register_var_cstr(track_vars_array, "REMOTE_ADDR", r->remote_addr ? r->remote_addr : "127.0.0.1");

    char port_buf[16];
    snprintf(port_buf, sizeof(port_buf), "%d", r->server_port > 0 ? r->server_port : 8080);
    register_var_cstr(track_vars_array, "SERVER_PORT", port_buf);

    if (r->content_type && r->content_type[0]) {
        register_var_cstr(track_vars_array, "CONTENT_TYPE", r->content_type);
    }
    char len_buf[32];
    snprintf(len_buf, sizeof(len_buf), "%zu", r->post_body_len);
    register_var_cstr(track_vars_array, "CONTENT_LENGTH", len_buf);

    if (r->cookie_header && r->cookie_header[0]) {
        register_var_cstr(track_vars_array, "HTTP_COOKIE", r->cookie_header);
    }

    if (r->extra_headers && r->extra_headers[0]) {
        char *copy = strdup(r->extra_headers);
        if (copy) {
            char *saveptr = NULL;
            char *line = strtok_r(copy, "\n", &saveptr);
            while (line) {
                char *eq = strchr(line, '=');
                if (eq && eq > line) {
                    *eq = '\0';
                    const char *k = line;
                    const char *v = eq + 1;
                    register_var_cstr(track_vars_array, k, v);
                }
                line = strtok_r(NULL, "\n", &saveptr);
            }
            free(copy);
        }
    }
}

static void abotalal_sapi_log_message(const char *message, int syslog_type_int) {
    (void)syslog_type_int;
    AbotalalSapiContext *ctx = g_active_ctx;
    if (ctx && message) {
        dynbuf_append(&ctx->out_stderr, message, strlen(message));
        dynbuf_append(&ctx->out_stderr, "\n", 1);
    }
    if (message) {
        ALOGE("%s", message);
    }
}

static int abotalal_sapi_startup(sapi_module_struct *sapi_module) {
    return php_module_startup(sapi_module, NULL);
}

static sapi_module_struct abotalal_sapi_module = {
    "abotalal-embed",                  /* name */
    "Abotalal Android Embedded PHP",   /* pretty name */
    abotalal_sapi_startup,             /* startup */
    php_module_shutdown_wrapper,       /* shutdown */
    NULL,                              /* activate */
    NULL,                              /* deactivate */
    abotalal_sapi_ub_write,            /* unbuffered write */
    abotalal_sapi_flush,               /* flush */
    NULL,                              /* get uid */
    NULL,                              /* getenv */
    php_error,                         /* error handler */
    abotalal_sapi_header_handler,      /* header handler */
    abotalal_sapi_send_headers,        /* send headers */
    NULL,                              /* send header */
    abotalal_sapi_read_post,           /* read POST data */
    abotalal_sapi_read_cookies,        /* read Cookies */
    abotalal_sapi_register_variables,  /* register server variables */
    abotalal_sapi_log_message,         /* Log message */
    NULL,                              /* Get request time */
    NULL,                              /* Child terminate */
    STANDARD_SAPI_MODULE_PROPERTIES
};

static int start_php_locked(const char *php_ini_path) {
    if (g_engine_started) {
        return 1;
    }

#if defined(SIGPIPE) && defined(SIG_IGN)
    signal(SIGPIPE, SIG_IGN);
#endif
    zend_signal_startup();

    sapi_startup(&abotalal_sapi_module);
    abotalal_sapi_module.ini_entries = DEFAULT_ANDROID_INI_ENTRIES;

    free(g_module_ini_override);
    g_module_ini_override = NULL;
    g_active_ini_path[0] = '\0';

    if (php_ini_path && php_ini_path[0]) {
        g_module_ini_override = strdup(php_ini_path);
        abotalal_sapi_module.php_ini_path_override = g_module_ini_override;
        snprintf(g_active_ini_path, sizeof(g_active_ini_path), "%s", php_ini_path);
    } else {
        abotalal_sapi_module.php_ini_path_override = NULL;
    }

    if (abotalal_sapi_module.startup(&abotalal_sapi_module) == FAILURE) {
        sapi_shutdown();
        free(g_module_ini_override);
        g_module_ini_override = NULL;
        return 0;
    }

    g_engine_started = 1;
    ALOGI("PHP %s Embedded SAPI initialized (ini=%s)", PHP_VERSION, g_active_ini_path);
    return 1;
}

static void stop_php_locked(void) {
    if (!g_engine_started) {
        return;
    }
    php_module_shutdown();
    sapi_shutdown();
    free(g_module_ini_override);
    g_module_ini_override = NULL;
    g_active_ini_path[0] = '\0';
    g_engine_started = 0;
    ALOGI("PHP %s Embedded SAPI shut down cleanly", PHP_VERSION);
}

static void apply_ini_override_str(const char *name, const char *value) {
    if (!name || !value || !value[0]) return;
    zend_string *k = zend_string_init(name, strlen(name), 0);
    zend_string *v = zend_string_init(value, strlen(value), 0);
    zend_alter_ini_entry(k, v, PHP_INI_ALL, PHP_INI_STAGE_ACTIVATE);
    zend_string_release(k);
    zend_string_release(v);
}

int abotalal_php_start(const char *php_ini_path) {
    pthread_mutex_lock(&g_php_mutex);
    int ok = start_php_locked(php_ini_path);
    pthread_mutex_unlock(&g_php_mutex);
    return ok;
}

void abotalal_php_stop(void) {
    pthread_mutex_lock(&g_php_mutex);
    stop_php_locked();
    pthread_mutex_unlock(&g_php_mutex);
}

int abotalal_php_is_running(void) {
    pthread_mutex_lock(&g_php_mutex);
    int running = g_engine_started;
    pthread_mutex_unlock(&g_php_mutex);
    return running;
}

const char *abotalal_php_get_version(void) {
    return PHP_VERSION;
}

int abotalal_php_get_modules(char *out_buf, size_t out_cap) {
    if (!out_buf || out_cap == 0) return 0;
    pthread_mutex_lock(&g_php_mutex);
    int started_here = 0;
    if (!g_engine_started) {
        if (!start_php_locked(NULL)) {
            pthread_mutex_unlock(&g_php_mutex);
            return 0;
        }
        started_here = 1;
    }

    size_t pos = 0;
    out_buf[0] = '\0';
    zend_module_entry *m;
    ZEND_HASH_FOREACH_PTR(&module_registry, m) {
        if (m && m->name) {
            size_t nlen = strlen(m->name);
            if (pos + nlen + 2 < out_cap) {
                if (pos > 0) out_buf[pos++] = ',';
                memcpy(out_buf + pos, m->name, nlen);
                pos += nlen;
                out_buf[pos] = '\0';
            }
        }
    } ZEND_HASH_FOREACH_END();

    if (started_here) {
        /* keep started for readiness unless explicitly stopped */
    }
    pthread_mutex_unlock(&g_php_mutex);
    return (int)pos;
}

int abotalal_php_execute_request(const AbotalalPhpRequest *req, AbotalalPhpResponse *resp) {
    if (!req || !resp || !req->script_path) return 0;

    memset(resp, 0, sizeof(*resp));
    resp->status_code = 500;

    pthread_mutex_lock(&g_php_mutex);

    const char *want_ini = (req->php_ini_path && req->php_ini_path[0]) ? req->php_ini_path : "";
    if (g_engine_started && strcmp(g_active_ini_path, want_ini) != 0) {
        stop_php_locked();
    }
    if (!g_engine_started) {
        if (!start_php_locked(want_ini[0] ? want_ini : NULL)) {
            resp->error_message = strdup("Failed to initialize PHP Embedded SAPI module");
            pthread_mutex_unlock(&g_php_mutex);
            return 0;
        }
    }

    AbotalalSapiContext ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.req = req;
    ctx.cookie_copy = (req->cookie_header && req->cookie_header[0]) ? strdup(req->cookie_header) : NULL;
    ctx.post_offset = 0;
    ctx.http_status = 200;
    dynbuf_init(&ctx.out_headers);
    dynbuf_init(&ctx.out_body);
    dynbuf_init(&ctx.out_stderr);

    g_active_ctx = &ctx;

    char orig_cwd[1024] = {0};
    int has_orig_cwd = (getcwd(orig_cwd, sizeof(orig_cwd)) != NULL);

    char *qstr_copy = strdup(req->query_string ? req->query_string : "");
    char *uri_copy = strdup(req->request_uri ? req->request_uri : "/");
    char *ctype_copy = req->content_type ? strdup(req->content_type) : NULL;
    char *path_copy = strdup(req->script_path);
    char *method_copy = strdup(req->method ? req->method : "GET");

    SG(server_context) = &ctx;
    SG(headers_sent) = 0;
    SG(request_info).no_headers = 0;
    SG(sapi_headers).http_response_code = 200;
    SG(request_info).request_method = method_copy;
    SG(request_info).query_string = qstr_copy;
    SG(request_info).request_uri = uri_copy;
    SG(request_info).path_translated = path_copy;
    SG(request_info).content_type = ctype_copy;
    SG(request_info).content_length = (zend_long)req->post_body_len;
    SG(request_info).cookie_data = ctx.cookie_copy;
    SG(request_info).argc = 0;
    SG(request_info).argv = NULL;

    int exec_ok = 0;
    if (php_request_startup() == FAILURE) {
        resp->error_message = strdup("php_request_startup() failed");
    } else {
        if (req->session_save_path && req->session_save_path[0]) {
            mkdir(req->session_save_path, 0700);
            apply_ini_override_str("session.save_path", req->session_save_path);
        }
        if (req->upload_tmp_dir && req->upload_tmp_dir[0]) {
            mkdir(req->upload_tmp_dir, 0700);
            apply_ini_override_str("upload_tmp_dir", req->upload_tmp_dir);
            apply_ini_override_str("sys_temp_dir", req->upload_tmp_dir);
        }
        if (req->document_root && req->document_root[0]) {
            apply_ini_override_str("doc_root", req->document_root);
        }

        zend_file_handle file_handle;
        zend_stream_init_filename(&file_handle, req->script_path);

        zend_first_try {
            if (php_execute_script(&file_handle) == SUCCESS) {
                exec_ok = 1;
            } else {
                exec_ok = 1;
            }
        } zend_catch {
            exec_ok = 1;
        } zend_end_try();

        if (!SG(headers_sent)) {
            sapi_send_headers();
        }
        php_request_shutdown(NULL);
    }

    if (has_orig_cwd) {
        if (chdir(orig_cwd) != 0) {
            /* ignore */
        }
    }

    g_active_ctx = NULL;
    SG(server_context) = NULL;
    SG(request_info).request_method = NULL;
    SG(request_info).query_string = NULL;
    SG(request_info).request_uri = NULL;
    SG(request_info).path_translated = NULL;
    SG(request_info).content_type = NULL;
    SG(request_info).cookie_data = NULL;

    free(qstr_copy);
    free(uri_copy);
    free(ctx.cookie_copy);
    free(ctype_copy);
    free(path_copy);
    free(method_copy);

    resp->status_code = ctx.http_status > 0 ? ctx.http_status : 200;
    resp->headers_text = ctx.out_headers.data;
    resp->headers_len = ctx.out_headers.len;
    resp->body = ctx.out_body.data;
    resp->body_len = ctx.out_body.len;
    resp->stderr_log = ctx.out_stderr.data;
    resp->success = exec_ok;

    pthread_mutex_unlock(&g_php_mutex);
    return exec_ok;
}

void abotalal_php_free_response(AbotalalPhpResponse *resp) {
    if (!resp) return;
    free(resp->headers_text);
    free(resp->body);
    free(resp->stderr_log);
    free(resp->error_message);
    memset(resp, 0, sizeof(*resp));
}
