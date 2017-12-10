**目录**
- Android Compatibility
 - Android Source Code
 - Compatibility Definition Document (CDD)
 - Compatibility Test Suite (CTS)
- CTS测试类型
- CTS测试涵盖领域
&emsp;
###**Android Compatibility**
&emsp; Android Compatibility又名Android兼容性计划，主要包括三个部分：Android源代码，Android兼容性定义文档（CDD文档），兼容性测试套件（CTS）。

####**Android Source Code**
&emsp; 众所周知，Android是开放源代码的，我们每个人都可以下载到Android源代码（科学上网），[Android Open Source Project](https://android-review.googlesource.com/q/status:open) 我们每个人都可以访问，且如果发现Google原生的代码有bug，还可以注册账号之后提交自己的修改等待Google的人review。

####**Compatibility Definition Document (CDD)**
&emsp; 但是也正因为Android源代码的开放性，众多手机厂商都在Android源代码的基础上添加了自己的定制，可能包括从Linux kernel层到上面的framework层都有修改，为了打造一个共同的生态系统，让不同的APP开发者开发出来的APP在所有的Android设备上都能正常运行，保证用户的使用体验，Google就提出了一些限制，这些限制就是CDD文档。

&emsp; [Compatibility Definition Document (CDD)](https://source.android.com/compatibility/android-cdd)这里是目前最新的Android 8.0上的CDD文档，里面定义了一系列Android设备的要求，包括手机，电视，手表等，还有一些很多其他硬件兼容和软件实现方面的要求，比如视频和音频的解码器，显示与绘图，相机等，屏幕尺寸（之前屏幕比例都是固定的，后来小米MIX的发布推动了Google修改了文档）想要详细了解的可以去Google的网站上面具体学习。

####**Compatibility Test Suite (CTS)**
&emsp; 在下载了Android源代码并进行定制之后，有了全新的的Android设备，如果想要发布设备，就需要开始执行CTS测试了。[Compatibility Test Suite](https://source.android.com/compatibility/cts/) 这里详细介绍了CTS的原理，流程，使用等，在通过了所有的测试case之后，需要提交一份报告给Google，等待Google那边approve之后，那就代表CTS测试这步已经OK了。

&emsp; 通过了前面的三个步骤，那么你的Android设备也就是Android兼容的设备了，也就是说你的新设备就可以发布了。
&emsp; 其实吧，说了这么多，Google这个Android兼容性计划的主要目的就是为了进行一些限制，毕竟Android源代码是开放的，Google无法控制所有的手机厂商怎么去修改源码，但是又得保证生态，总不能相同的APP在有些Android设备上可以正常使用但是在另外的Android设备上完全无法安装或运行，或者相同的Android软件在不同的设备上给用户的体验完全不一致。为了打造这样一个共同的Android生态环境，才有了这个Android兼容性计划。

###**CTS测试类型**
从case类型上划分，测试类型主要有以下四种：
**单元测试**：即基本API测试，比如HashMap，ArrayList等单个类。
**组合API测试**：测试一系列组合API，比如四大组件的使用。
**稳定性测试**：测试系统在压力下的耐久性。
**性能测试**：根据定义的基准测试系统的性能，例如每秒渲染帧数。

从执行测试的方式上划分，主要有以下两种：
**PC端跑plan**：这种测试是通过在PC端通过命令行的方式组织所有的测试case，然后在手机上执行，最终收集所有的测试结果。
**CtsVerify**：这种测试是为了弥补上面无法测试到的类型，是安装一个apk到被测试的设备上，需要人为输入，比如GPS，传感器等的测试。

###**CTS测试涵盖的领域**
![这里写图片描述](http://img.blog.csdn.net/20171202151557181?watermark/2/text/aHR0cDovL2Jsb2cuY3Nkbi5uZXQvdTAxMTczMzg2OQ==/font/5a6L5L2T/fontsize/400/fill/I0JBQkFCMA==/dissolve/70/gravity/SouthEast)

&emsp;
本文参考链接：https://source.android.com/compatibility/
