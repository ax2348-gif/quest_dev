#include <ctype.h>
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char **argv) {
    int check = argc == 3 && strcmp(argv[1], "--check") == 0;
    if ((!check && argc != 2) || (argc == 2 && strcmp(argv[1], "--help") == 0)) {
        fprintf(stderr, "Usage: quest-launch [--check] package/activity\n");
        return argc == 2 ? 0 : 2;
    }
    const char *component = argv[check ? 2 : 1];
    if (!strchr(component, '/') || strlen(component) > 512) {
        fprintf(stderr, "Expected package/activity (maximum 512 bytes)\n"); return 2;
    }
    for (const unsigned char *p = (const unsigned char *)component; *p; p++) {
        if (!(isalnum(*p) || *p == '.' || *p == '/' || *p == '_' || *p == '$')) {
            fprintf(stderr, "Unsupported component character\n"); return 2;
        }
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }
    struct timeval timeout = {.tv_sec = 8};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    const char *name = "dev.quest.runtime.control";
    memcpy(addr.sun_path + 1, name, strlen(name));
    if (connect(fd, (struct sockaddr *)&addr,
                offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name)) < 0) {
        perror("runtime control socket (is the SSH service running?)"); close(fd); return 1;
    }
    char request[640];
    int len = snprintf(request, sizeof(request), "{\"action\":\"%s\",\"component\":\"%s\"}\n",
                       check ? "check" : "launch", component);
    int sent = 0;
    while (sent < len) {
        ssize_t n = send(fd, request + sent, len - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { perror("send"); close(fd); return 1; }
        sent += (int)n;
    }
    char reply[4096];
    size_t used = 0;
    while (used < sizeof(reply) - 1) {
        ssize_t n = read(fd, reply + used, sizeof(reply) - 1 - used);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        used += (size_t)n;
        if (memchr(reply, '\n', used)) break;
    }
    close(fd); reply[used] = 0;
    if (!used) { fprintf(stderr, "No reply from runtime launch bridge\n"); return 1; }
    fputs(reply, stdout);
    return strstr(reply, "\"ok\":true") ? 0 : 1;
}
