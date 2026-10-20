#include <jni.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <termios.h>
#include <sys/ioctl.h>

#define JNI_FN(name) Java_dev_quest_runtime_TerminalSession_##name
JNIEXPORT jint JNICALL JNI_FN(openPty)(JNIEnv *env,jclass klass) {
    (void)env;(void)klass;
    int fd=posix_openpt(O_RDWR|O_NOCTTY|O_CLOEXEC|O_NONBLOCK);
    if(fd<0)return -1;
    if(grantpt(fd)||unlockpt(fd)){close(fd);return -1;}
    return fd;
}
JNIEXPORT jstring JNICALL JNI_FN(slaveName)(JNIEnv *env,jclass klass,jint fd) {
    (void)klass;char path[128];if(ptsname_r(fd,path,sizeof(path)))return NULL;
    return (*env)->NewStringUTF(env,path);
}
JNIEXPORT jbyteArray JNICALL JNI_FN(readPty)(JNIEnv *env,jclass klass,jint fd) {
    (void)klass;struct pollfd p={.fd=fd,.events=POLLIN};int n;
    do{n=poll(&p,1,200);}while(n<0&&errno==EINTR);
    if(n<0)return NULL;
    unsigned char data[8192];ssize_t size=0;
    if(n>0){
        do{size=read(fd,data,sizeof(data));}while(size<0&&errno==EINTR);
        if(size<0&&(errno==EAGAIN||errno==EWOULDBLOCK))size=0;
        else if(size<=0)return NULL;
    }
    jbyteArray result=(*env)->NewByteArray(env,(jsize)size);
    if(result&&size)(*env)->SetByteArrayRegion(env,result,0,(jsize)size,(jbyte*)data);
    return result;
}
JNIEXPORT jint JNICALL JNI_FN(writePty)(JNIEnv *env,jclass klass,jint fd,jbyteArray bytes) {
    (void)klass;jsize length=(*env)->GetArrayLength(env,bytes);
    if(length>65536)return -1;
    jbyte *data=(*env)->GetByteArrayElements(env,bytes,NULL);if(!data)return -1;
    int sent=0;
    while(sent<length){
        ssize_t n=write(fd,data+sent,(size_t)(length-sent));
        if(n>0){sent+=(int)n;continue;}
        if(n<0&&errno==EINTR)continue;
        if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK)){
            struct pollfd p={.fd=fd,.events=POLLOUT};
            if(poll(&p,1,500)>0)continue;
        }
        break;
    }
    (*env)->ReleaseByteArrayElements(env,bytes,data,JNI_ABORT);return sent;
}
JNIEXPORT void JNICALL JNI_FN(resizePty)(JNIEnv *env,jclass klass,jint fd,jint cols,jint rows) {
    (void)env;(void)klass;
    struct winsize size={.ws_row=(unsigned short)rows,.ws_col=(unsigned short)cols};
    ioctl(fd,TIOCSWINSZ,&size);
}
JNIEXPORT void JNICALL JNI_FN(closePty)(JNIEnv *env,jclass klass,jint fd) {
    (void)env;(void)klass;close(fd);
}
