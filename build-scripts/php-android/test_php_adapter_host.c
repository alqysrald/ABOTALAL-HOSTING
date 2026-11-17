#include "abotalal_php_adapter.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void write_file(const char *path, const char *content) {
    FILE *f = fopen(path, "wb");
    assert(f != NULL);
    fwrite(content, 1, strlen(content), f);
    fclose(f);
}

int main(void) {
    char tmp_dir[] = "/tmp/abotalal_php_host_test_XXXXXX";
    assert(mkdtemp(tmp_dir) != NULL);

    char sess_dir[512], upload_dir[512], ini_path[512];
    snprintf(sess_dir, sizeof(sess_dir), "%s/sessions", tmp_dir);
    snprintf(upload_dir, sizeof(upload_dir), "%s/uploads", tmp_dir);
    snprintf(ini_path, sizeof(ini_path), "%s/php.ini", tmp_dir);
    mkdir(sess_dir, 0755);
    mkdir(upload_dir, 0755);

    write_file(ini_path,
        "display_errors=1\n"
        "error_reporting=E_ALL\n"
        "memory_limit=128M\n"
        "date.timezone=Asia/Riyadh\n");

    /* 1 & 2. Load & initialize PHP engine */
    assert(abotalal_php_start(ini_path) == 1);
    assert(abotalal_php_is_running() == 1);
    const char *ver = abotalal_php_get_version();
    printf("[1-2] Engine started: PHP %s\n", ver);
    assert(strncmp(ver, "8.3.", 4) == 0);

    /* 3. Execute phpinfo() */
    char f_info[512];
    snprintf(f_info, sizeof(f_info), "%s/info.php", tmp_dir);
    write_file(f_info, "<?php phpinfo(INFO_GENERAL); ?>");
    AbotalalPhpRequest req = {0};
    AbotalalPhpResponse resp = {0};
    req.script_path = f_info;
    req.document_root = tmp_dir;
    req.php_ini_path = ini_path;
    req.session_save_path = sess_dir;
    req.upload_tmp_dir = upload_dir;
    req.method = "GET";
    req.request_uri = "/info.php";
    req.script_name = "/info.php";
    req.http_host = "127.0.0.1";
    req.server_port = 8080;
    assert(abotalal_php_execute_request(&req, &resp) == 1);
    assert(resp.status_code == 200);
    assert(resp.body && strstr(resp.body, "Abotalal Android Embedded PHP") != NULL);
    assert(strstr(resp.body, "PHP Version") != NULL);
    printf("[3] phpinfo() passed (%zu bytes)\n", resp.body_len);
    abotalal_php_free_response(&resp);

    /* 4. Execute arithmetic */
    char f_math[512];
    snprintf(f_math, sizeof(f_math), "%s/math.php", tmp_dir);
    write_file(f_math, "<?php $r = (125 * 4) + (900 / 3) - 18; echo 'MATH=' . $r; ?>");
    req.script_path = f_math;
    req.request_uri = "/math.php";
    assert(abotalal_php_execute_request(&req, &resp) == 1);
    assert(resp.body && strcmp(resp.body, "MATH=782") == 0);
    printf("[4] Arithmetic passed: %s\n", resp.body);
    abotalal_php_free_response(&resp);

    /* 5. Allowed file read/write inside document_root */
    char f_rw[512], rw_target[512];
    snprintf(f_rw, sizeof(f_rw), "%s/rw.php", tmp_dir);
    snprintf(rw_target, sizeof(rw_target), "%s/data_out.txt", tmp_dir);
    write_file(f_rw,
        "<?php file_put_contents(__DIR__ . '/data_out.txt', 'ABOTALAL_FILE_OK'); "
        "echo file_get_contents(__DIR__ . '/data_out.txt'); ?>");
    req.script_path = f_rw;
    req.request_uri = "/rw.php";
    assert(abotalal_php_execute_request(&req, &resp) == 1);
    assert(resp.body && strcmp(resp.body, "ABOTALAL_FILE_OK") == 0);
    printf("[5] File read/write passed: %s\n", resp.body);
    abotalal_php_free_response(&resp);

    /* 6. Test PHP Extensions (JSON, filter, ctype, hash, reflection/etc.) */
    char mods[4096] = {0};
    int mod_len = abotalal_php_get_modules(mods, sizeof(mods));
    assert(mod_len > 10);
    printf("[6a] Loaded PHP modules: %s\n", mods);

    char f_ext[512];
    snprintf(f_ext, sizeof(f_ext), "%s/ext.php", tmp_dir);
    write_file(f_ext,
        "<?php echo json_encode(['json'=>function_exists('json_encode'), "
        "'ctype'=>ctype_digit('12345'), 'hash'=>strlen(hash('sha256','abc'))]); ?>");
    req.script_path = f_ext;
    req.request_uri = "/ext.php";
    assert(abotalal_php_execute_request(&req, &resp) == 1);
    assert(resp.body && strstr(resp.body, "\"json\":true") != NULL);
    assert(resp.body && strstr(resp.body, "\"ctype\":true") != NULL);
    assert(resp.body && strstr(resp.body, "\"hash\":64") != NULL);
    printf("[6b] Extensions check passed: %s\n", resp.body);
    abotalal_php_free_response(&resp);

    /* 7. GET query string + Cookie + Session set */
    char f_get[512];
    snprintf(f_get, sizeof(f_get), "%s/get_sess.php", tmp_dir);
    write_file(f_get,
        "<?php session_start(); $_SESSION['user'] = 'abotalal'; "
        "setcookie('theme', 'dark', 0, '/'); "
        "echo 'GET=' . ($_GET['site'] ?? '') . '|ID=' . ($_GET['id'] ?? ''); ?>");
    req.script_path = f_get;
    req.method = "GET";
    req.request_uri = "/get_sess.php?site=blog&id=99";
    req.query_string = "site=blog&id=99";
    assert(abotalal_php_execute_request(&req, &resp) == 1);
    assert(resp.body && strcmp(resp.body, "GET=blog|ID=99") == 0);
    assert(resp.headers_text && strstr(resp.headers_text, "Set-Cookie:") != NULL);
    assert(resp.headers_text && strstr(resp.headers_text, "PHPSESSID=") != NULL);
    assert(resp.headers_text && strstr(resp.headers_text, "theme=dark") != NULL);
    printf("[7] GET + Session + Set-Cookie passed:\n%s", resp.headers_text);

    /* Extract PHPSESSID from Set-Cookie to verify session persistence across requests */
    char *psess = strstr(resp.headers_text, "PHPSESSID=");
    char sess_cookie[256] = {0};
    assert(psess != NULL);
    sscanf(psess, "PHPSESSID=%255[^;\r\n]", sess_cookie);
    char full_cookie[300];
    snprintf(full_cookie, sizeof(full_cookie), "PHPSESSID=%s; theme=dark", sess_cookie);
    abotalal_php_free_response(&resp);

    /* 8. POST + Cookie + Session restoration */
    char f_post[512];
    snprintf(f_post, sizeof(f_post), "%s/post.php", tmp_dir);
    write_file(f_post,
        "<?php session_start(); "
        "echo 'POST=' . ($_POST['action'] ?? '') . '|SESS=' . ($_SESSION['user'] ?? '') . '|CK=' . ($_COOKIE['theme'] ?? ''); ?>");
    const char *post_payload = "action=deploy_site&port=8080";
    req.script_path = f_post;
    req.method = "POST";
    req.request_uri = "/post.php";
    req.query_string = "";
    req.content_type = "application/x-www-form-urlencoded";
    req.cookie_header = full_cookie;
    req.post_body = post_payload;
    req.post_body_len = strlen(post_payload);
    assert(abotalal_php_execute_request(&req, &resp) == 1);
    assert(resp.body && strcmp(resp.body, "POST=deploy_site|SESS=abotalal|CK=dark") == 0);
    printf("[8] POST + Session + Cookie restoration passed: %s\n", resp.body);
    abotalal_php_free_response(&resp);

    /* 10. Stop engine, verify stopped, restart, and re-run PHP */
    abotalal_php_stop();
    assert(abotalal_php_is_running() == 0);
    assert(abotalal_php_start(ini_path) == 1);
    assert(abotalal_php_is_running() == 1);
    req.method = "GET";
    req.script_path = f_math;
    req.content_type = NULL;
    req.cookie_header = NULL;
    req.post_body = NULL;
    req.post_body_len = 0;
    assert(abotalal_php_execute_request(&req, &resp) == 1);
    assert(resp.body && strcmp(resp.body, "MATH=782") == 0);
    printf("[10] Stop & restart engine passed: %s\n", resp.body);
    abotalal_php_free_response(&resp);
    abotalal_php_stop();

    printf("ALL HOST SAPI TESTS PASSED!\n");
    return 0;
}
