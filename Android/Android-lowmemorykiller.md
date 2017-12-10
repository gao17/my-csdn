##**目录**
 - 概述
 - lmkd
 - lowmemorykiller
 - 总结
&emsp;

##**1.概述**
&emsp;Android底层还是基于Linux，在Linux中低内存是会有oom killer去杀掉一些进程去释放内存，而Android中的lowmemorykiller就是在此基础上做了一些调整来的。因为手机上的内存毕竟比较有限，而Android中APP在不使用之后并不是马上被杀掉，虽然上层ActivityManagerService中也有很多关于进程的调度以及杀进程的手段，但是毕竟还需要考虑手机剩余内存的实际情况，lowmemorykiller的作用就是当内存比较紧张的时候去及时杀掉一些ActivityManagerService还没来得及杀掉但是对用户来说不那么重要的进程，回收一些内存，保证手机的正常运行。

lowmemkiller中会涉及到几个重要的概念：
`/sys/module/lowmemorykiller/parameters/minfree`：里面是以","分割的一组数，每个数字代表一个内存级别
`/sys/module/lowmemorykiller/parameters/adj`：对应上面的一组数，每个数组代表一个进程优先级级别
举个例子：
/sys/module/lowmemorykiller/parameters/minfree：18432,23040,27648,32256,55296,80640
/sys/module/lowmemorykiller/parameters/adj：0,100,200,300,900,906

代表的意思：两组数一一对应，当手机内存低于80640时，就去杀掉优先级906以及以上级别的进程，当内存低于55296时，就去杀掉优先级900以及以上的进程。

对每个进程来说：
/proc/pid/oom_adj：代表当前进程的优先级，这个优先级是kernel中的优先级，这个优先级与上层的优先级之间有一个换算，文章最后会提一下。
/proc/pid/oom_score_adj：上层优先级，跟ProcessList中的优先级对应
##**2.init进程lmkd**
>代码位置:platform/system/core/lmkd/

ProcessList中定义有进程的优先级，越重要的进程的优先级越低，前台APP的优先级为0，系统APP的优先级一般都是负值，所以一般进程管理以及杀进程都是针对与上层的APP来说的，而这些进程的优先级调整都在AMS里面，AMS根据进程中的组件的状态去不断的计算每个进程的优先级，计算之后，会及时更新到对应进程的文件节点中，而这个对文件节点的更新并不是它完成的，而是lmkd，他们之间通过socket通信。
lmkd在手机中是一个常驻进程，用来处理上层ActivityManager在进行updateOomAdj之后，通过socket与lmkd进行通信，更新进程的优先级，如果必要则杀掉进程释放内存。lmkd是在init进程启动的时候启动的，在lmkd中有定义lmkd.rc:
```
service lmkd /system/bin/lmkd
    class core
    group root readproc
    critical
    socket lmkd seqpacket 0660 system system
    writepid /dev/cpuset/system-background/tasks
```
上层AMS跟lmkd通信主要分为三种command，每种command代表一种数据控制方式，在ProcessList以及lmkd中都有定义：
```
LMK_TARGET：更新/sys/module/lowmemorykiller/parameters/中的minfree以及adj
LMK_PROCPRIO：更新指定进程的优先级，也就是oom_score_adj
LMK_PROCREMOVE：移除进程
```
在开始介绍lmkd的处理逻辑之前，lmkd.c中有几个重要的变量与数据结构提前说明一下：
``` c
// 内存级别限额
#define INKERNEL_MINFREE_PATH "/sys/module/lowmemorykiller/parameters/minfree"
// 不同级别内存对应要杀的的优先级
#define INKERNEL_ADJ_PATH "/sys/module/lowmemorykiller/parameters/adj"
 
// 装载上面两组数字的数组
static int lowmem_adj[MAX_TARGETS];
static int lowmem_minfree[MAX_TARGETS];
 
// 三种command
enum lmk_cmd {
    LMK_TARGET,
    LMK_PROCPRIO,
    LMK_PROCREMOVE,
};
 
// 优先级的最小值
#define OOM_SCORE_ADJ_MIN       (-1000)
// 优先级最大值
#define OOM_SCORE_ADJ_MAX       1000
 
// 双向链表结构体
struct adjslot_list {
    struct adjslot_list *next;
    struct adjslot_list *prev;
};
 
// 进程在lmkd中的数据结构体
struct proc {
    struct adjslot_list asl;
    int pid;
    uid_t uid;
    int oomadj;
    struct proc *pidhash_next;
};
 
// 存放进程proc的hashtable，index是通过pid的计算得出
static struct proc *pidhash[PIDHASH_SZ];
 
// 根据pid计算index的hash算法
#define pid_hashfn(x) ((((x) >> 8) ^ (x)) & (PIDHASH_SZ - 1))
 
// 进程优先级到数组的index之间的转换
// 因为进程的优先级可以是负值，但是数组的index不能为负值
// 不过因为这个转换只是简单加了1000，为了方便，后面的描述中就认为是优先级直接做了index
#define ADJTOSLOT(adj) (adj + -OOM_SCORE_ADJ_MIN)
 
// table，类似hashtable，不过计算index的方式不是hash，而是oom_score_adj经过转换后直接作为index
// 数组的每个元素都是双向循环链表
// 进程的优先级作为数组的index
// 即以进程的优先级为index，从-1000到+1000 + 1大小的数组，根据优先级，同优先级的进程index相同
// 每个元素是一个双向链表，这个链表上的所有proc的优先级都相同
// 这样根据优先级杀进程的时候就会非常方便，要杀指定优先级的进程可以根据优先级获取到一个进程链表，逐个去杀。
static struct adjslot_list procadjslot_list[ADJTOSLOT(OOM_SCORE_ADJ_MAX) + 1];
```


###**2.1 lmkd进程启动入口**
``` c
int main(int argc __unused, char **argv __unused) {
    struct sched_param param = {
            .sched_priority = 1,
    };
    // 将此进程未来使用到的所有内存都锁在物理内存中，防止内存被交换
    mlockall(MCL_FUTURE);
    // 设置此线程的调度策略为SCHED_FIFO，first-in-first-out，param中主要设置sched_priority
    // 由于SCHED_FIFO是一种实时调度策略，在这个策略下优先级从1(low) -> 99(high)
    // 实时线程通常会比普通线程有更高的优先级
    sched_setscheduler(0, SCHED_FIFO, &param);
    // 初始化epoll以及与ActivityManager的socket连接,等待cmd和data
    if (!init())
        // 进入死循环epoll_wait等待fd事件
        mainloop();
    ALOGI("exiting");
    return 0;
}
```
前面已经提到，这个进程存在的主要作用是跟AMS进行通信，更新oomAdj，在必要的时候杀掉进程。所以在main函数中主要就是创建了epoll以及初始化socket并连接ActivityManager，然后阻塞等待上层传递cmd以及数据过来。
###**2.2 init初始化**
``` c
static int init(void) {
    ...
    
    // 拿到lmkd的socket fd
    ctrl_lfd = android_get_control_socket("lmkd");
    if (ctrl_lfd < 0) {
        ALOGE("get lmkd control socket failed");
        return -1;
    }
    // server listen
    ret = listen(ctrl_lfd, 1);
    if (ret < 0) {
        ALOGE("lmkd control socket listen failed (errno=%d)", errno);
        return -1;
    }
    epev.events = EPOLLIN;
    // ctrl_connect_handler里面完成了soclet的accpet以及read数据，并对数据进行相应的处理
    epev.data.ptr = (void *)ctrl_connect_handler;
    if (epoll_ctl(epollfd, EPOLL_CTL_ADD, ctrl_lfd, &epev) == -1) {
        ALOGE("epoll_ctl for lmkd control socket failed (errno=%d)", errno);
        return -1;
    }
    maxevents++;
    // 使用kernel空间的处理
    use_inkernel_interface = !access(INKERNEL_MINFREE_PATH, W_OK);

    if (use_inkernel_interface) {
        ALOGI("Using in-kernel low memory killer interface");
    } else {
        ret = init_mp(MEMPRESSURE_WATCH_LEVEL, (void *)&mp_event);
        if (ret)
            ALOGE("Kernel does not support memory pressure events or in-kernel low memory killer");
    }

    // 双向链表初始化
    for (i = 0; i <= ADJTOSLOT(OOM_SCORE_ADJ_MAX); i++) {
        procadjslot_list[i].next = &procadjslot_list[i];
        procadjslot_list[i].prev = &procadjslot_list[i];
    }
    return 0;
}
```
在初始化的时候，有一个很重要的判断：use_inkernel_interface，这个是根据是否有`/sys/module/lowmemorykiller/parameters/minfree`的写权限来判断的，没有的情况下就使用kernel空间的逻辑
目前遇到的都是use_inkernel_interface
如果use_inkernel_interface的值为false：
``` c
// 不使用kernel interface时，init_mp初始化
 
static int init_mp(char *levelstr, void *event_handler)
{
    ...
    // 读取文件节点/dev/memcg/下的属性值 -- memory.pressure_level
    mpfd = open(MEMCG_SYSFS_PATH "memory.pressure_level", O_RDONLY | O_CLOEXEC);
    if (mpfd < 0) {
        ALOGI("No kernel memory.pressure_level support (errno=%d)", errno);
        goto err_open_mpfd;
    }
    // 写入cgroup.event_control
    evctlfd = open(MEMCG_SYSFS_PATH "cgroup.event_control", O_WRONLY | O_CLOEXEC);
    if (evctlfd < 0) {
        ALOGI("No kernel memory cgroup event control (errno=%d)", errno);
        goto err_open_evctlfd;
    }
 
    evfd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (evfd < 0) {
        ALOGE("eventfd failed for level %s; errno=%d", levelstr, errno);
        goto err_eventfd;
    }
 
    ret = snprintf(buf, sizeof(buf), "%d %d %s", evfd, mpfd, levelstr);
    if (ret >= (ssize_t)sizeof(buf)) {
        ALOGE("cgroup.event_control line overflow for level %s", levelstr);
        goto err;
    }
 
    ret = write(evctlfd, buf, strlen(buf) + 1);
    if (ret == -1) {
        ALOGE("cgroup.event_control write failed for level %s; errno=%d",
              levelstr, errno);
        goto err;
    }
 
    epev.events = EPOLLIN;
    epev.data.ptr = event_handler;
    ret = epoll_ctl(epollfd, EPOLL_CTL_ADD, evfd, &epev);
    if (ret == -1) {
        ALOGE("epoll_ctl for level %s failed; errno=%d", levelstr, errno);
        goto err;
    }
    maxevents++;
    mpevfd = evfd;
    return 0;
 
err:
    close(evfd);
err_eventfd:
    close(evctlfd);
err_open_evctlfd:
    close(mpfd);
err_open_mpfd:
    return -1;
}
 
 
// 获取手机当前的内存状态，如果内存匹配minfree中的等级，则开始杀该等级对应的优先级的进程
static void mp_event(uint32_t events __unused) {
    ...
    ret = read(mpevfd, &evcount, sizeof(evcount));
    if (ret < 0)
        ALOGE("Error reading memory pressure event fd; errno=%d",
              errno);
 
    if (time(NULL) - kill_lasttime < KILL_TIMEOUT)
        return;
 
    while (zoneinfo_parse(&mi) < 0) {
        // Failed to read /proc/zoneinfo, assume ENOMEM and kill something
        // 通过read /proc/zoneinfo，获取当前的内存状态
        find_and_kill_process(0, 0, true);
    }
 
    other_free = mi.nr_free_pages - mi.totalreserve_pages;
    other_file = mi.nr_file_pages - mi.nr_shmem;
 
    do {
        killed_size = find_and_kill_process(other_free, other_file, first);
        if (killed_size > 0) {
            first = false;
            other_free += killed_size;
            other_file += killed_size;
        }
    } while (killed_size > 0);
}
```
###**2.3 进入loop循环mainloop**
``` c
// 进入死循环，然后调用epoll_wait阻塞等待事件的到来
static void mainloop(void) {
    while (1) {
        struct epoll_event events[maxevents];
        int nevents;
        int i;
        ctrl_dfd_reopened = 0;
        nevents = epoll_wait(epollfd, events, maxevents, -1);
 
        if (nevents == -1) {
            if (errno == EINTR)
                continue;
            ALOGE("epoll_wait failed (errno=%d)", errno);
            continue;
        }
 
        for (i = 0; i < nevents; ++i) {
            if (events[i].events & EPOLLERR)
                ALOGD("EPOLLERR on event #%d", i);
            if (events[i].data.ptr)
                (*(void (*)(uint32_t))events[i].data.ptr)(events[i].events);
        }
    }
}
```
###**2.4 处理socket传递过来的数据ctrl_command_handler**
前面在`ctrl_connect_handler`这个方法中处理了accept，并开始了`ctrl_data_handler`中读取数据并进行处理：`ctrl_command_handler`。对于ActivityManager传递来的Command以及data的主要处理逻辑就在`ctrl_command_handler`中。
``` c
static void ctrl_command_handler(void) {
    int ibuf[CTRL_PACKET_MAX / sizeof(int)];
    int len;
    int cmd = -1;
    int nargs;
    int targets;

    len = ctrl_data_read((char *)ibuf, CTRL_PACKET_MAX);
    if (len <= 0)
        return;

    nargs = len / sizeof(int) - 1;
    if (nargs < 0)
        goto wronglen;

    cmd = ntohl(ibuf[0]);
    
    // 一共三种command，在前面静态变量的定义处已经介绍过
    switch(cmd) {
    // 更新内存级别以及对应级别的进程adj
    case LMK_TARGET:
        targets = nargs / 2;
        if (nargs & 0x1 || targets > (int)ARRAY_SIZE(lowmem_adj))
            goto wronglen;
        cmd_target(targets, &ibuf[1]);
        break;
    // 根据pid更新adj
    case LMK_PROCPRIO:
        if (nargs != 3)
            goto wronglen;
        cmd_procprio(ntohl(ibuf[1]), ntohl(ibuf[2]), ntohl(ibuf[3]));
        break;
    // 根据pid移除proc
    case LMK_PROCREMOVE:
        if (nargs != 1)
            goto wronglen;
        cmd_procremove(ntohl(ibuf[1]));
        break;
    default:
        ALOGE("Received unknown command code %d", cmd);
        return;
    }

    return;

wronglen:
    ALOGE("Wrong control socket read length cmd=%d len=%d", cmd, len);
}
```
上层代码的调用时机这里就不细化了，往前追的话基本都是在ActivityManagerService中的udpateOomAdj中，也就是说上层根据四大组件的状态对进程的优先级进行调整之后，会及时的反应到lmkd中，在内存不足的时候触发杀进程，会从低优先级开始杀进程。command一共有三种，在上层的代码是在ProcessList中。
####**2.4.1 LMK_TARGET**
``` c
// 上层逻辑是在ProcessList.updateOomLevels中
ByteBuffer buf = ByteBuffer.allocate(4 * (2*mOomAdj.length + 1));
buf.putInt(LMK_TARGET);
for (int i=0; i<mOomAdj.length; i++) {
    buf.putInt((mOomMinFree[i]*1024)/PAGE_SIZE);
    buf.putInt(mOomAdj[i]);
}
writeLmkd(buf)
 
// lmkd处理逻辑
static void cmd_target(int ntargets, int *params) {
    int i;
    if (ntargets > (int)ARRAY_SIZE(lowmem_adj))
        return;
    // 这个for循环对应上面的for循环，将数据读出装进数组中
    for (i = 0; i < ntargets; i++) {
        lowmem_minfree[i] = ntohl(*params++);
        lowmem_adj[i] = ntohl(*params++);
    }
    lowmem_targets_size = ntargets;
    // 使用kernel空间的处理逻辑
    if (use_inkernel_interface) {
        char minfreestr[128];
        char killpriostr[128];
        minfreestr[0] = '\0';
        killpriostr[0] = '\0';
        // 取出两个数组中的数据，以","分隔，分别拼接成string
        for (i = 0; i < lowmem_targets_size; i++) {
            char val[40];
            if (i) {
                strlcat(minfreestr, ",", sizeof(minfreestr));
                strlcat(killpriostr, ",", sizeof(killpriostr));
            }
            snprintf(val, sizeof(val), "%d", lowmem_minfree[i]);
            strlcat(minfreestr, val, sizeof(minfreestr));
            snprintf(val, sizeof(val), "%d", lowmem_adj[i]);
            strlcat(killpriostr, val, sizeof(killpriostr));
        }
        // 将生成好的string写入到文件节点minfree以及adj
        writefilestring(INKERNEL_MINFREE_PATH, minfreestr);
        writefilestring(INKERNEL_ADJ_PATH, killpriostr);
    }
}
```
上面的处理逻辑主要是：
1. 按照顺序取出数据，装进lmkd的数组中。
2. 分别将两个数组中的数取出，用","分隔
3. lowmem_minfree中的数据拼成的string写到 "/sys/module/lowmemorykiller/parameters/minfree"
4. lowmem_adj中的数据拼成的string写到 "/sys/module/lowmemorykiller/parameters/adj"
####**2.4.2 LMK_PROCPRIO**
``` c
// 上层逻辑是在ProcessList.setOomAdj中
public static final void setOomAdj(int pid, int uid, int amt) {
    if (amt == UNKNOWN_ADJ)
        return;
 
    long start = SystemClock.elapsedRealtime();
    ByteBuffer buf = ByteBuffer.allocate(4 * 4);
    buf.putInt(LMK_PROCPRIO);
    buf.putInt(pid);
    buf.putInt(uid);
    buf.putInt(amt);
    writeLmkd(buf);
    long now = SystemClock.elapsedRealtime();
    if ((now-start) > 250) {
        Slog.w("ActivityManager", "SLOW OOM ADJ: " + (now-start) + "ms for pid " + pid
                + " = " + amt);
    }
}
 
// lmkd处理逻辑
static void cmd_procprio(int pid, int uid, int oomadj) {
    struct proc *procp;
    char path[80];
    char val[20];
    if (oomadj < OOM_SCORE_ADJ_MIN || oomadj > OOM_SCORE_ADJ_MAX) {
        ALOGE("Invalid PROCPRIO oomadj argument %d", oomadj);
        return;
    }
    // LMK_PROCPRIO的主要作用就是更新进程的oomAdj
    // 将上层传递过来的数据（pid以及优先级）写到该进程对应的文件节点
    // /proc/pid/oom_score_adj
    snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);
    snprintf(val, sizeof(val), "%d", oomadj);
    writefilestring(path, val);
    // 如果使用kernel的使用逻辑，return
    // 即这个command传递过来只是更新了对应文件节点的oom_score_adj
    if (use_inkernel_interface)
        return;
    // 从hashtable中查找proc
    procp = pid_lookup(pid);
    // 如果没有查找到，也就是说这个进程是新创建的，lmkd维护的数据结构中还没有这个proc，因此需要新建并添加到hashtable中
    if (!procp) {
            procp = malloc(sizeof(struct proc));
            if (!procp) {
                // Oh, the irony.  May need to rebuild our state.
                return;
            }
            procp->pid = pid;
            procp->uid = uid;
            procp->oomadj = oomadj;
            // 将proc插入到lmkd中的数据结构中，主要包括两个数据结构
            // 更新hashtable，通过pid计算hash值，然后存储，解决冲突是让新来的作为数组元素链表的头结点
            // 优先级为index的双向链表组成的table
            proc_insert(procp);
    } else {
        // hashtable中已经有这个proc
        // 但是因为优先级的变化，需要先把这个proc从原先的优先级table中对应位置的双向链表中remove
        // 然后新加到新的优先级对应的双向链表中
        // 双向链表的添加是新来的放在头部
        proc_unslot(procp);
        procp->oomadj = oomadj;
        proc_slot(procp);
    }
}
 
// 其中pid_lookup：查询hashtable，因为进程的pid是唯一的，然后从中取出该pid在lmkd中的proc结构体。
static struct proc *pid_lookup(int pid) {
    struct proc *procp;
    for (procp = pidhash[pid_hashfn(pid)]; procp && procp->pid != pid;
         procp = procp->pidhash_next)
            ;
    return procp;
}
```

####**2.4.3 LMK_PROCREMOVE**
``` c
// 上层处理逻辑在ProcessList.remove中
public static final void remove(int pid) {
    ByteBuffer buf = ByteBuffer.allocate(4 * 2);
    buf.putInt(LMK_PROCREMOVE);
    buf.putInt(pid);
    writeLmkd(buf);
}
 
// lmkd处理逻辑
static void cmd_procremove(int pid) {
    // 如果使用kernel接口,return
    if (use_inkernel_interface)
        return;
    // 更新数据结构，pid的hashtable以及进程优先级的双向链表table
    pid_remove(pid);
    kill_lasttime = 0;
}
 
static int pid_remove(int pid) {
    int hval = pid_hashfn(pid);
    struct proc *procp;
    struct proc *prevp;
    // pid的hashtable
    for (procp = pidhash[hval], prevp = NULL; procp && procp->pid != pid;
         procp = procp->pidhash_next)
            prevp = procp;
    if (!procp)
        return -1;
    if (!prevp)
        pidhash[hval] = procp->pidhash_next;
    else
        prevp->pidhash_next = procp->pidhash_next;
    // 进程优先级的table
    proc_unslot(procp);
    free(procp);
    return 0;
}
```
####**2.4.4 小结**
从上面的处理逻辑就能看出来，三种command的处理逻辑中都对use_inkernel_interface的情况下做了特殊处理，在use_inkernel_interface的情况下，做的事情都是很简单的，只是更新一下文件节点。如果不使用kernel interface，就需要lmkd自己维护两个table，在每次更新adj的时候去更新table。 且在初始化的时候也能看到，如果不使用kernel的lowmemorykiller，则需要lmkd自己获取手机内存状态，如果匹配到了minfree中的等级，则需要通过杀掉一些进程释放内存。
###**2.5 杀进程**
初始化的时候已经注册好了，当获取到手机的内存匹配到minfree中某一个级别时：
####**2.5.1 查找**
```
// 不使用kernel interface
// 根据当前内存的状态查找需要杀掉的进程
static int find_and_kill_process(int other_free, int other_file, bool first)
{
    ...
    // 主要逻辑是这里的for循环
    // 根据前面最小内存级别与优先级的对应关系
    // 拿到需要杀的进程的优先级
    for (i = 0; i < lowmem_targets_size; i++) {
        minfree = lowmem_minfree[i];
        if (other_free < minfree && other_file < minfree) {
            min_score_adj = lowmem_adj[i];
            break;
        }
    }
    if (min_score_adj == OOM_SCORE_ADJ_MAX + 1)
        return 0;
    for (i = OOM_SCORE_ADJ_MAX; i >= min_score_adj; i--) {
        struct proc *procp;
retry:
        // 从优先级table中取出一个
        // 因为是双向循环链表，取的时候取出head->prev，也就是最后一个
        // 也就是使用的lru算法，先把近期不用的进程杀掉
        procp = proc_adj_lru(i);
        if (procp) {
            // 杀进程，通过发信号的方式
            // 返回值是杀了该进程之后释放的内存的大小
            // 如果释放内存之后依然不满足要求，则从链表上再取一个杀
            killed_size = kill_one_process(procp, other_free, other_file, minfree, min_score_adj, first);
            if (killed_size < 0) {
                goto retry;
            } else {
                return killed_size;
            }
        }
    }
    return 0;
}
```
####**2.5.2 杀进程**
这里的逻辑比较简单，主要是将这个proc从数据结构中删除，也就是两个table，删除之后直接发信号杀进程。可以看到这个地方杀进程之后是有log的，可以在logcat中查看是否有lmk杀进程。
``` c
static int kill_one_process(struct proc *procp, int other_free, int other_file,
        int minfree, int min_score_adj, bool first)
{
    int pid = procp->pid;
    uid_t uid = procp->uid;
    char *taskname;
    int tasksize;
    int r;

    taskname = proc_get_name(pid);
    if (!taskname) {
        pid_remove(pid);
        return -1;
    }
    // 通过读取/proc/pid/statm
    tasksize = proc_get_size(pid);
    if (tasksize <= 0) {
        pid_remove(pid);
        return -1;
    }
    ALOGI("Killing '%s' (%d), uid %d, adj %d\n"
          "   to free %ldkB because cache %s%ldkB is below limit %ldkB for oom_adj %d\n"
          "   Free memory is %s%ldkB %s reserved",
          taskname, pid, uid, procp->oomadj, tasksize * page_k,
          first ? "" : "~", other_file * page_k, minfree * page_k, min_score_adj,
          first ? "" : "~", other_free * page_k, other_free >= 0 ? "above" : "below");
    // send signal SIGKILL
    r = kill(pid, SIGKILL);
    killProcessGroup(uid, pid, SIGKILL);
    pid_remove(pid);
    if (r) {
        ALOGE("kill(%d): errno=%d", procp->pid, errno);
        return -1;
    } else {
        return tasksize;
    }
}
```
###**2.6 小结**
这部分从lmkd的main开始，从一些数据结构的初始化，到进入loop，再到与ActivityManager的socket连接，接收上层传递的数据，然后分别根据三种command做出不同的更新与删除等。当然最重要的还是use_inkernel_interface这个变量，从初始化到所有命令的处理都与这个逻辑分不开，如果不使用的话，需要自维护进程的数据结构，需要读取文件节点获取手机内存状态，在minfree匹配到时去查找并杀进程，直到释放足够多的内存。在使用kernel空间lowmemorykiller的情况下，三种命令做的事情会非常有限，主要是更新文件节点，而lmdk本身根本不需要维护任何跟进程相关的结构，判断手机状态并查找低优先级的进程以及杀进程的工作全部都由lowmemorykiller完成。
##**3. lowmemorykiller**
前面也提过，大多情况其实是使用kernel interface的，其实也就是kernel中的lowmemorykiller，代码位置在：`/kernel/msm-3.18/drivers/staging/android/lowmemorykiller.c`
lowmemorykiller中是通过linux的shrinker实现的，这个是linux的内存回收机制的一种，由内核线程kswapd负责监控，在lowmemorykiller初始化的时候注册register_shrinker。
``` c
static int __init lowmem_init(void)
{
	register_shrinker(&lowmem_shrinker);
	vmpressure_notifier_register(&lmk_vmpr_nb);
	return 0;
}
```
minfree以及min_adj两个数组：
``` c
// 下面两个数组分别代表了两个参数文件中的默认值，数组默认的size都是6
// 对应 "/sys/module/lowmemorykiller/parameters/adj"
static short lowmem_adj[6] = {
	0,
	1,
	6,
	12,
};
static int lowmem_adj_size = 4;

// 对应 "/sys/module/lowmemorykiller/parameters/minfree"
static int lowmem_minfree[6] = {
	3 * 512,	/* 6MB */
	2 * 1024,	/* 8MB */
	4 * 1024,	/* 16MB */
	16 * 1024,	/* 64MB */
};
static int lowmem_minfree_size = 4;
```

扫描当前内存以及杀进程：
``` c
static unsigned long lowmem_scan(struct shrinker *s, struct shrink_control *sc)
{
	struct task_struct *tsk;
	struct task_struct *selected = NULL;
	unsigned long rem = 0;
	int tasksize;
	int i;
	// OOM_SCORE_ADJ_MAX = 1000
	short min_score_adj = OOM_SCORE_ADJ_MAX + 1;
	int minfree = 0;
	int selected_tasksize = 0;
	short selected_oom_score_adj;
	// array_size = 6
	int array_size = ARRAY_SIZE(lowmem_adj);
	// NR_FREE_PAGES 是在/kernel/msm-3.18/include/linux/mmzone.h中定义的zone_stat_item对应的第一个枚举，下面的枚举以此类推
	// global_page_state(NR_FREE_PAGES)即读取/proc/vmstat 中第一行的值
	int other_free = global_page_state(NR_FREE_PAGES) - totalreserve_pages;
	int other_file = global_page_state(NR_FILE_PAGES) -
						global_page_state(NR_SHMEM) -
						global_page_state(NR_UNEVICTABLE) -
						total_swapcache_pages();

	if (lowmem_adj_size < array_size)
		array_size = lowmem_adj_size;
	if (lowmem_minfree_size < array_size)
		array_size = lowmem_minfree_size;
	for (i = 0; i < array_size; i++) {
	    // 从小到大扫描lowmem_minfree数组，根据剩余内存的大小，确定当前剩余内存的级别
		minfree = lowmem_minfree[i];
		if (other_free < minfree && other_file < (minfree + minfree / 4)) {
		    // 由于两个数组之间的对应关系，minfree中找到当前内存所处的等级之后
		    // 也就可以在lowmem_adj获取到在这个内存级别需要杀掉的进程的优先级
			min_score_adj = lowmem_adj[i];
			break;
		}
	}

	lowmem_print(3, "lowmem_scan %lu, %x, ofree %d %d, ma %hd\n",
		     sc->nr_to_scan, sc->gfp_mask, other_free,
		     other_file, min_score_adj);
    // 经过一轮扫描，发现不需要杀进程，return
	if (min_score_adj == OOM_SCORE_ADJ_MAX + 1) {
		lowmem_print(5, "lowmem_scan %lu, %x, return 0\n",
			     sc->nr_to_scan, sc->gfp_mask);
		return 0;
	}

	selected_oom_score_adj = min_score_adj;
    // 内核一种同步机制 -- RCU同步机制
	rcu_read_lock();
again:
    // for_each_process用来遍历所有的进程
    // 定义在 /kernel/msm-3.18/include/linux/sched.h
    // #define for_each_process(p) \
    // 	for (p = &init_task ; (p = next_task(p)) != &init_task ; )
	for_each_process(tsk) {
		struct task_struct *p;
		short oom_score_adj;
        // 内核线程kthread
		if (tsk->flags & PF_KTHREAD)
			continue;
        // 已经被杀，还在等锁
		if (test_tsk_lmk_waiting(tsk)) {
			lowmem_print(2, "%s (%d) is already killed, skip\n",
				tsk->comm, tsk->pid);
			continue;
		}
        // 一个task
        // 定义在 /kernel/msm-3.18/mm/oom_kill.c
		p = find_lock_task_mm(tsk);
		if (!p)
			continue;

		oom_score_adj = p->signal->oom_score_adj;
		if (oom_score_adj < min_score_adj) {
		    // 如果当前找到的进程的oom_score_adj比当前需要杀的最小优先级还低，不杀
			task_unlock(p);
			continue;
		}
		// 拿到占用的内存大小
		// 定义在 /kernel/msm-3.18/include/linux/mm.h
		tasksize = get_mm_rss(p->mm);
#ifdef CONFIG_ZRAM
		tasksize += (get_mm_counter(p->mm, MM_SWAPENTS) / 3);
#endif
		task_unlock(p);
		if (tasksize <= 0)
			continue;
		if (selected) {
		// 第一次不会进到这
		// 第二次，也就是循环回来，判断如果当前选中的进程的adj更小
		// 或优先级相同但是内存比较小，则continue
			if (oom_score_adj < selected_oom_score_adj)
				continue;
			if (oom_score_adj == selected_oom_score_adj &&
			    tasksize <= selected_tasksize)
				continue;
		}
		selected = p;
		selected_tasksize = tasksize;
		selected_oom_score_adj = oom_score_adj;
		// 已经选中了进程p，准备kill
		lowmem_print(2, "select '%s' (%d, %d), adj %hd, size %d, to kill\n",
			     p->comm, p->pid, p->tgid, oom_score_adj, tasksize);
	}
	if (selected) {
		task_lock(selected);
		// 给该进程发信号 SIGKILL
		send_sig(SIGKILL, selected, 0);
		if (selected->mm)
			task_set_lmk_waiting(selected);
		task_unlock(selected);
		// 杀进程完毕，打印kernel log, tag是lowmemorykiller
		lowmem_print(1, "Killing '%s' (%d), adj %hd,\n"
				 "   to free %ldkB on behalf of '%s' (%d) because\n"
				 "   cache %ldkB is below limit %ldkB for oom_score_adj %hd\n"
				 "   Free memory is %ldkB above reserved\n",
			     selected->comm, selected->pid,
			     selected_oom_score_adj,
			     selected_tasksize * (long)(PAGE_SIZE / 1024),
			     current->comm, current->pid,
			     other_file * (long)(PAGE_SIZE / 1024),
			     minfree * (long)(PAGE_SIZE / 1024),
			     min_score_adj,
			     other_free * (long)(PAGE_SIZE / 1024));
		lowmem_deathpending_timeout = jiffies + HZ;
		// 释放的内存大小
		rem += selected_tasksize;
	}
    // 如果需要杀掉多个进程
    // kill_one_more在lmk_vmpressure_notifier中置true
	if (kill_one_more) {
		selected = NULL;
		kill_one_more = false;
		lowmem_print(1, "lowmem_scan kill one more process\n");
		// 跳转到遍历的地方再开始
		goto again;
	}
	lowmem_print(4, "lowmem_scan %lu, %x, return %lu\n",
		     sc->nr_to_scan, sc->gfp_mask, rem);
	rcu_read_unlock();
	return rem;
}
```
lmk_vmpressure_notifier中定义了什么时候去`kill_one_more`，主要是当内存压力在**95**以上时
lmk_vmpressure_notifier这个也是在init时注册：`vmpressure_notifier_register(&lmk_vmpr_nb);`
``` c
static int lmk_vmpressure_notifier(struct notifier_block *nb,
			unsigned long action, void *data)
{
	unsigned long pressure = action;

	if (pressure >= 95) {
		if (!kill_one_more) {
			kill_one_more = true;
			lowmem_print(2, "vmpressure %ld, set kill_one_more true\n",
				pressure);
		}
	} else {
		if (kill_one_more) {
			kill_one_more = false;
			lowmem_print(2, "vmpressure %ld, set kill_one_more false\n",
				pressure);
		}
	}
	return 0;
}
```
oom_adj到oom_score_adj的转换：
``` c
static short lowmem_oom_adj_to_oom_score_adj(short oom_adj)
{
	if (oom_adj == OOM_ADJUST_MAX)
		return OOM_SCORE_ADJ_MAX;
	else
		return (oom_adj * OOM_SCORE_ADJ_MAX) / -OOM_DISABLE;
}
```
##**4. 总结**
由于Android中的进程启动的很频繁，四大组件都会涉及到进程启动，进程启动之后做完组要做的事情之后就会很快被AMS把优先级降低，但是为了针对低内存的情况以及如果用户开启太多，且APP的优先级很高，AMS这边就有一些无力了，为了保证手机正常运行必须有进程清理，内存回收，根据当前手机剩余内存的状态，在minfree中找到当前等级，再根据这个等级去adj中找到这个等级应该杀掉的进程的优先级，然后去杀进程，直到释放足够的内存。目前大多都使用kernel中的lowmemorykiller，但是上层用户的APP的优先级的调整还是AMS来完成的，lmkd在中间充当了一个桥梁的角色，通过把上层的更新之后的adj写入到文件节点，提供lowmemorykiller杀进程的依据。

