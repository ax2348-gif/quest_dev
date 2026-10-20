#include <android/native_activity.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "quest_native_app.h"

/* Deliberately never dlclose: callbacks/threads may still reference the app.
 * Every swap happens in a fresh :xr process. */
static void *app_handle;
static char status_path[PATH_MAX];
static char log_path[PATH_MAX];
static void quote(FILE *f, const char *s) {
    fputc('"', f);
    for (const unsigned char *p=(const unsigned char *)s;*p;p++) {
        if (*p=='"' || *p=='\\') { fputc('\\',f); fputc(*p,f); }
        else if (*p<32) fprintf(f,"\\u%04x",*p);
        else fputc(*p,f);
    }
    fputc('"', f);
}
static void report(const char *state, const char *path, const char *detail) {
    __android_log_print(ANDROID_LOG_INFO,"QuestNativeHost","%s: %s (%s)",state,detail,path);
    FILE *log=fopen(log_path,"a");
    if(log){fprintf(log,"pid=%d %s: %s (%s)\n",getpid(),state,detail,path);fclose(log);}
    char temp[PATH_MAX];
    if(snprintf(temp,sizeof(temp),"%s.tmp.%d",status_path,getpid()) >= (int)sizeof(temp))return;
    FILE *f=fopen(temp,"w");if(!f)return;
    fprintf(f,"{\"pid\":%d,\"state\":",getpid());quote(f,state);
    fputs(",\"library\":",f);quote(f,path);fputs(",\"detail\":",f);quote(f,detail);
    fputs("}\n",f);fflush(f);fsync(fileno(f));fclose(f);rename(temp,status_path);
}
JNIEXPORT void ANativeActivity_onCreate(ANativeActivity *activity, void *saved, size_t saved_size) {
    char selection[PATH_MAX], library[PATH_MAX]="libquesttelemetry.so";
    snprintf(selection,sizeof(selection),"%s/native/selected",activity->internalDataPath);
    snprintf(status_path,sizeof(status_path),"%s/native/load-status.json",activity->internalDataPath);
    snprintf(log_path,sizeof(log_path),"%s/native/host.log",activity->internalDataPath);
    FILE *f=fopen(selection,"r");
    if(f) {
        if(!fgets(library,sizeof(library),f))library[0]=0;
        fclose(f);library[strcspn(library,"\r\n")]=0;
    }
    report("loading",library,"Opening native app");
    app_handle=dlopen(library,RTLD_NOW|RTLD_LOCAL);
    if(!app_handle){report("error",library,dlerror());ANativeActivity_finish(activity);return;}
    const QuestNativeAppV1 *(*query)(void) = (const QuestNativeAppV1 *(*)(void))dlsym(app_handle,"quest_native_app_v1");
    void (*entry)(ANativeActivity *,void *,size_t) = (void (*)(ANativeActivity *,void *,size_t))dlsym(app_handle,"ANativeActivity_onCreate");
    if(!query||!entry){report("error",library,"Missing quest_native_app_v1 or ANativeActivity_onCreate");ANativeActivity_finish(activity);return;}
    const QuestNativeAppV1 *info=query();
    if(!info||info->abi_version!=QUEST_NATIVE_ABI_VERSION||info->struct_size<sizeof(*info)||!info->name){
        report("error",library,"Incompatible native app ABI");ANativeActivity_finish(activity);return;
    }
    report("entering",library,info->name);
    entry(activity,saved,saved_size);
    /* This establishes entry return, NOT an XR session or tracked frames. */
    report("entry-returned",library,info->name);
}
