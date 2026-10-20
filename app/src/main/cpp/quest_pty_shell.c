/* Exec helper: no fork inside the Android JVM, and no UID/GID changes. */
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <termios.h>
int main(int argc,char **argv){
    if(argc!=2){fprintf(stderr,"Usage: quest-pty-shell /dev/pts/N\n");return 2;}
    if(setsid()<0){perror("setsid");return 1;}
    int fd=open(argv[1],O_RDWR);
    if(fd<0){perror("open PTY slave");return 1;}
    if(ioctl(fd,TIOCSCTTY,0)<0){perror("TIOCSCTTY");return 1;}
    for(int i=0;i<3;i++)if(dup2(fd,i)<0){perror("dup2");return 1;}
    if(fd>2)close(fd);
    char *args[]={"/system/bin/sh","-l","-i",NULL};
    execv(args[0],args);perror("exec sh");return 1;
}
