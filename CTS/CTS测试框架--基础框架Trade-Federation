##**目录**
1. 基础框架介绍
2. 命令的支持与组织 -- RegexTrie
3. 框架入口 -- Console
4. 命令与配置文件解析 -- Configuration
5. 命令调度 -- CommandScheduler 
6. 命令解析执行 -- InvocationThread

&emsp; 在接下来几篇文章中将陆续介绍基础框架的原理，主要是上面这些内容。

###1. 基础框架介绍
&emsp; CTS测试框架其实是在一个基础框架，也就是这个Trade-Federation的基础上，进行了二次开发与封装，添加了测试case以及通过plan对测试case的管理。所以要介绍CTS测试框架，首先必须先说一下这个Trade-Federation，这个框架也在AOSP的源码里面，可以在[这里](https://android.googlesource.com/platform/tools/tradefederation/+/master)下载到。如果不能科学上网，可以在[这里](http://download.csdn.net/download/u011733869/10141942)下载我之前调试的版本，里面包括了依赖的jar包，下载之后导入eclipse即可。另外，[这里](http://download.csdn.net/download/u011733869/10141958)还有CTS测试V1版本的框架源码，也就是在基础框架的基础上进行的二次封装。

&emsp; 这个基础框架就是为手机测试准备的，里面提供了很多支持，包括设备的连接，对多设备的管理，手机logcat的收集等。不过这个基础框架的编译与运行是不依赖Android环境的，在PC端就可以运行，不过因为是专门用来针对Android设备进行测试的，所以如果有测试case要测试，还是需要Android设备的。具体Trade-Federation的官方介绍包括简单入门使用可以点击[这里](https://source.android.com/devices/tech/test_infra/tradefed/)。

###2. 基本概念
&emsp; 在开始之前，有必要先提一下一些基本的概念，方便整体上先有一个全局的认识。

**GlobalConfiguration**：因为基础框架是一个Java程序，这个GlobalConfiguration就代表的是在一次框架运行的时候使用的全局配置，基础框架提供了默认实现，也可以通过xml文件自己配置。

**Command**：顾名思义，命令。在测试框架跑起来之后，我们需要在控制台输入命令才能开始测试的执行。

**Configuration**：类似与前面的GlobalConfiguration，不过不同的是GlobalConfiguration指的是一次程序运行过程中使用的配置，而Configuration则指的是一条命令运行时使用的配置。

一条可执行的命令一般是这种形式：
![这里写图片描述](http://img.blog.csdn.net/20171202163530344?watermark/2/text/aHR0cDovL2Jsb2cuY3Nkbi5uZXQvdTAxMTczMzg2OQ==/font/5a6L5L2T/fontsize/400/fill/I0JBQkFCMA==/dissolve/70/gravity/SouthEast)

`cts-tf >`：代表提示符，可以自己配置。
`run`：代表命令，这个命令是基础框架就支持的。
`第一个cts`：就是Configuration，代表本条命令的运行使用一个名为cts的配置文件。
`--plan`：这个是option，需要添加参数的选项。
`第二个cts`：这个是第三个参数option的value。

除了run之外，还有很多其他的命令，常用的主要是list，dump，exit等，这些命令都是基础框架都支持的，此外，命令的支持不仅包括全拼（比如`list devices`可以查看目前所有的Android设备），还可以使用简写（`l d `和 `list devices`效果相同）。

下一篇正式开始介绍基础测试框架的原理，详细讲述基础框架对命令的组织与支持。
&emsp;

本文参考链接：
https://android.googlesource.com/platform/tools/tradefederation/+/master
https://source.android.com/devices/tech/test_infra/tradefed/

