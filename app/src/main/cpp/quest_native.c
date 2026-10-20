#include <ctype.h>
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <limits.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2 || strcmp(argv[1], "--help") == 0) {
        fprintf(stderr, "Usage: quest-native run /path/app.so | restart | builtin | stop | status\n");
        return argc < 2 ? 2 : 0;
    }
    const char *op=argv[1];
    int list=strcmp(op,"run")!=0;
    if ((list && argc!=2) || (!list && argc!=3) ||
        (strcmp(op,"run") && strcmp(op,"restart") && strcmp(op,"builtin") && strcmp(op,"stop") && strcmp(op,"status"))) {
        fprintf(stderr,"Invalid operation or arguments; see quest-native --help\n");return 2;
    }
    const char *value=list?"":argv[2];
    char absolute[PATH_MAX];
    if(!strcmp(op,"run")) {
        if(!realpath(value,absolute)){perror("Library path");return 2;}
        value=absolute;
    }
    if(strlen(value)>1500){fprintf(stderr,"Argument too long\n");return 2;}
    char escaped[3100];size_t j=0;
    for(const unsigned char *p=(const unsigned char *)value;*p;p++) {
        if(*p<32){fprintf(stderr,"Control character in argument\n");return 2;}
        if(*p=='"'||*p=='\\')escaped[j++]='\\';
        escaped[j++]=*p;
    }
    escaped[j]=0;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }
    struct timeval timeout = {.tv_sec = 120};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    const char *name = "dev.quest.runtime.control";
    memcpy(addr.sun_path + 1, name, strlen(name));
    if (connect(fd, (struct sockaddr *)&addr,
                offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name)) < 0) {
        perror("runtime control socket (is the SSH service running?)"); close(fd); return 1;
    }
    char request[3300];
    int len=snprintf(request,sizeof(request),"{\"action\":\"native-%s\",\"value\":\"%s\"}\n",op,escaped);
    int sent = 0;
    while (sent < len) {
        ssize_t n = send(fd, request + sent, len - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { perror("send"); close(fd); return 1; }
        sent += (int)n;
    }
    char reply[65536];
    size_t used = 0;
    while (used < sizeof(reply) - 1) {
        ssize_t n = read(fd, reply + used, sizeof(reply) - 1 - used);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        used += (size_t)n;
        if (memchr(reply, '\n', used)) break;
    }
    close(fd); reply[used] = 0;
    if (!used) { fprintf(stderr, "No reply from runtime control bridge\n"); return 1; }
    fputs(reply, stdout);
    return strstr(reply, "\"ok\":true") ? 0 : 1;
}
