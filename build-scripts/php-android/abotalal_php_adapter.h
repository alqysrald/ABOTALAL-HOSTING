#ifndef ABOTALAL_PHP_ADAPTER_H
#define ABOTALAL_PHP_ADAPTER_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AbotalalPhpRequest {
    const char *script_path;
    const char *document_root;
    const char *php_ini_path;
    const char *session_save_path;
    const char *upload_tmp_dir;
    const char *method;
    const char *request_uri;
    const char *query_string;
    const char *script_name;
    const char *http_host;
    int server_port;
    const char *remote_addr;
    const char *content_type;
    const char *cookie_header;
    const char *extra_headers; /* newline-delimited HEADER_NAME=VALUE lines */
    const char *post_body;
    size_t post_body_len;
} AbotalalPhpRequest;

typedef struct AbotalalPhpResponse {
    int status_code;
    char *headers_text;
    size_t headers_len;
    char *body;
    size_t body_len;
    char *stderr_log;
    int success;
    char *error_message;
} AbotalalPhpResponse;

int abotalal_php_start(const char *php_ini_path);
int abotalal_php_execute_request(const AbotalalPhpRequest *req, AbotalalPhpResponse *resp);
void abotalal_php_stop(void);
int abotalal_php_is_running(void);
const char *abotalal_php_get_version(void);
int abotalal_php_get_modules(char *out_buf, size_t out_cap);
void abotalal_php_free_response(AbotalalPhpResponse *resp);

#ifdef __cplusplus
}
#endif

#endif /* ABOTALAL_PHP_ADAPTER_H */
