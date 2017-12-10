##目录

- 整体流程概览
- Main
- Console
- 总结

&emsp;从这篇文章往后开始介绍整个框架的运行流程原理。
##1.整体流程概览
这里是整个测试框架的大纲流程图，其中主要涉及到四个线程：
1. main -- 启动入口
2. Console -- 处理命令
3. CommandScheduler -- 命令调度
4. InvocationThrad -- 执行命令
![这里写图片描述](http://img.blog.csdn.net/20171210124458734?watermark/2/text/aHR0cDovL2Jsb2cuY3Nkbi5uZXQvdTAxMTczMzg2OQ==/font/5a6L5L2T/fontsize/400/fill/I0JBQkFCMA==/dissolve/70/gravity/SouthEast)
##2.main
整个测试框架作为一个java程序，在eclipse中可以直接运行，入口在com.android.tradefed.command包下的Console中：
``` java
public static void main(final String[] mainArgs) throws InterruptedException,
        ConfigurationException {
    Console console = new Console();
    startConsole(console, mainArgs);
}
```
可以看到这里做的事情很简单，在main线程中启动了一个console，那么这个Console到底是什么呢？
``` java
protected Console() {
    this(getReader());
}
// 构造方法中初始化了一系列变量
Console(ConsoleReader reader) {
    super("TfConsole");
    mConsoleStartTime = System.currentTimeMillis();
    mConsoleReader = reader;
    if (reader != null) {
        mConsoleReader.addCompletor(
                new ConfigCompletor(getConfigurationFactory().getConfigList()));
    }
    // HelpList初始化
    List<String> genericHelp = new LinkedList<String>();
    // helpString：放入每个command支持的使用方式
    Map<String, String> commandHelp = new LinkedHashMap<String, String>();
    // 添加默认支持的命令，使用的就是前面介绍的RegexTrie
    addDefaultCommands(mCommandTrie, genericHelp, commandHelp);
    // 这个是个空方法，主要是为了方便子类复写添加自己的命令
    setCustomCommands(mCommandTrie, genericHelp, commandHelp);
    // 生成HelpList
    generateHelpListings(mCommandTrie, genericHelp, commandHelp);
}

public static void startConsole(Console console, String[] args) throws InterruptedException,
        ConfigurationException {
    // 创建GlobalConfiguration
    List<String> nonGlobalArgs = GlobalConfiguration.createGlobalConfiguration(args);
    console.setArgs(nonGlobalArgs);
    // 创建命令调度器
    console.setCommandScheduler(GlobalConfiguration.getInstance().getCommandScheduler());
    console.setKeyStoreFactory(GlobalConfiguration.getInstance().getKeyStoreFactory());
    将console线程设为daemon线程
    console.setDaemon(true);
    // 启动console线程
    console.start();
    // Wait for the CommandScheduler to get started before we exit the main thread.  See full
    // explanation near the top of #run()
    // 等待CommandSchedler启动，然后退出
    console.awaitScheduler();
}
```
上面主要作用就是初始化了一些配置，然后启动了console线程，下面来看下这个GlobalConfiguration
``` java
public static List<String> createGlobalConfiguration(String[] args)
        throws ConfigurationException {
    synchronized (sInstanceLock) {
        if (sInstance != null) {
            throw new IllegalStateException("GlobalConfiguration is already initialized!");
        }
        List<String> nonGlobalArgs = new ArrayList<String>(args.length);
        // 初始化ConfigurationFactory的单例
        // GlobalConfiguration以及后面的Congiguration共用
        IConfigurationFactory configFactory = ConfigurationFactory.getInstance();
        String globalConfigPath = getGlobalConfigPath();
        // 重点是这个方法，从参数创建configuration
        sInstance = configFactory.createGlobalConfigurationFromArgs(
                ArrayUtil.buildArray(new String[] {globalConfigPath}, args), nonGlobalArgs);
        // 
        if (!DEFAULT_EMPTY_CONFIG_NAME.equals(globalConfigPath)) {
            // Only print when using different from default
            System.out.format("Success!  Using global config \"%s\"\n", globalConfigPath);
        }
        // Validate that madatory options have been set
        sInstance.validateOptions();
        return nonGlobalArgs;
    }
}

private static String getGlobalConfigPath() {
    String path = System.getenv(GLOBAL_CONFIG_VARIABLE);
    if (path != null) {
        System.out.format(
                "Attempting to use global config \"%s\" from variable $%s.\n",
                path, GLOBAL_CONFIG_VARIABLE);
        return path;
    }
    File file = new File(GLOBAL_CONFIG_FILENAME);
    if (file.exists()) {
        path = file.getPath();
        System.out.format("Attempting to use autodetected global config \"%s\".\n", path);
        return path;
    }
    return DEFAULT_EMPTY_CONFIG_NAME;
}

private static final String GLOBAL_CONFIG_VARIABLE = "TF_GLOBAL_CONFIG";
private static final String GLOBAL_CONFIG_FILENAME = "tf_global_config.xml";
// Empty embedded configuration available by default
private static final String DEFAULT_EMPTY_CONFIG_NAME = "empty";

// configurtionFactory中的重点方法，解析参数创建Iconfiguration
public IGlobalConfiguration createGlobalConfigurationFromArgs(String[] arrayArgs,
        List<String> remainingArgs) throws ConfigurationException {
    List<String> listArgs = new ArrayList<String>(arrayArgs.length);
    IGlobalConfiguration config = internalCreateGlobalConfigurationFromArgs(arrayArgs,
            listArgs);
    remainingArgs.addAll(config.setOptionsFromCommandLineArgs(listArgs));
    return config;
}

private IGlobalConfiguration internalCreateGlobalConfigurationFromArgs(String[] arrayArgs,
        List<String> optionArgsRef) throws ConfigurationException {
    if (arrayArgs.length == 0) {
        throw new ConfigurationException("Configuration to run was not specified");
    }
    optionArgsRef.addAll(Arrays.asList(arrayArgs));
    // first arg is config name
    final String configName = optionArgsRef.remove(0);
    ConfigurationDef configDef = getConfigurationDef(configName, true, null);
    return configDef.createGlobalConfiguration();
}
// 由于这里默认的为空，详细的解析逻辑这里不再过多介绍
// 因为后面对配置文件创建configuration的时候也走这个逻辑
// 会根据具体的xml文件详细介绍解析以及装载的流程
// 目前只需要知道其实还是调用了GlobalConfiguration的构造方法
IGlobalConfiguration createGlobalConfiguration() throws ConfigurationException {
    IGlobalConfiguration config = new GlobalConfiguration(getName(), getDescription());
    for (Map.Entry<String, List<ConfigObjectDef>> objClassEntry : mObjectClassMap.entrySet()) {
        List<Object> objectList = new ArrayList<Object>(objClassEntry.getValue().size());
        for (ConfigObjectDef configDef : objClassEntry.getValue()) {
            Object configObject = createObject(objClassEntry.getKey(), configDef.mClassName);
            objectList.add(configObject);
        }
        config.setConfigurationObjectList(objClassEntry.getKey(), objectList);
    }
    for (OptionDef optionEntry : mOptionList) {
        config.injectOptionValue(optionEntry.name, optionEntry.key, optionEntry.value);
    }
    return config;
}
```
其实就是初始化的时候去查找了看默认的xml配置文件tf_global_config.xml是否存在，不存在的情况下就使用默认的配置：
``` java
GlobalConfiguration(String name, String description) {
    mName = name;
    mDescription = description;
    // 配置map，key为全局配置的组件的string，value为组件
    mConfigMap = new LinkedHashMap<String, List<Object>>();
    // 配置map，key为支持的option
    mOptionMap = new MultiMap<String, String>();
    setHostOptions(new HostOptions());
    setDeviceRequirements(new DeviceSelectionOptions());
    // 设备管理
    setDeviceManager(new DeviceManager());
    // 初始化命令调度器
    setCommandScheduler(new CommandScheduler());
    setKeyStoreFactory(new StubKeyStoreFactory());
    setShardingStrategy(new StrictShardHelper());
}
```
main这里看起来对GlobalConfiguration的操作很复杂，不过在默认情况下，配置文件不存在，也就是使用了默认的配置，最终就是调用了GlobalConfiguration的构造方法而已。不管是GlobalConfiguration还是Configuration，构建都是通过ConfigurationFactory，通过解析xml文件封装对象。在后面介绍具体的configuration的创建的时候就会根据具体的xml文件详细介绍装载过程。
main中的逻辑主要是创建了GlobalConfiguration，启动了console线程，其中有一点很重要：设置console为daemon线程
>daemon线程就是在虚拟机中没有其他线程的时候会自动退出的线程。

因为Console线程主要是为了读取用户输入的，设为daemon线程就能保证在其他调度以及运行线程都退出时，Console线程也跟着退出。但是为了防止main线程退出之后，还没有其他线程的启动，Console线程会直接退出，因此最后main会等待CommandScheduler的启动，然后在退出，这里使用的是CountDownLatch。
##3.Console线程
``` java
public void run() {
    List<String> arrrgs = mMainArgs;
    if (mScheduler == null) {
        throw new IllegalStateException("command scheduler hasn't been set");
    }
    try {
        // 判断控制台
        if (!isConsoleFunctional()) {
            if (arrrgs.isEmpty()) {
                printLine("No commands for non-interactive mode; exiting.");
                // FIXME: need to run the scheduler here so that the things blocking on it
                // FIXME: will be released.
                mScheduler.start();
                mScheduler.await();
                return;
            } else {
                printLine("Non-interactive mode: Running initial command then exiting.");
                mShouldExit = true;
            }
        }
        // 先把CommandScheduler启动起来，当CommandScheduler启动，main就会退出
        mScheduler.start();
        mScheduler.await();
        String input = "";
        CaptureList groups = new CaptureList();
        String[] tokens;
        do { // 循环
            if (arrrgs.isEmpty()) {
	            // 读取控制台的输入
                input = getConsoleInput();
                if (input == null) {
                    // Usually the result of getting EOF on the console
                    printLine("");
                    printLine("Received EOF; quitting...");
                    mShouldExit = true;
                    break;
                }
                tokens = null;
                try {
	                // 格式化输入的命令，用空格分割，装进一个数组
                    tokens = QuotationAwareTokenizer.tokenizeLine(input);
                } catch (IllegalArgumentException e) {
                    printLine(String.format("Invalid input: %s.", input));
                    continue;
                }
                if (tokens == null || tokens.length == 0) {
                    continue;
                }
            } else {
                printLine(String.format("Using commandline arguments as starting command: %s",
                        arrrgs));
                if (mConsoleReader != null) {
                    final String cmd = ArrayUtil.join(" ", arrrgs);
                    mConsoleReader.getHistory().addToHistory(cmd);
                }
                tokens = arrrgs.toArray(new String[0]);
                if (arrrgs.get(0).matches(HELP_PATTERN)) {
                    // if started from command line for help, return to shell
                    mShouldExit = true;
                }
                arrrgs = Collections.emptyList();
            }
            // 很重要的一步，从command的RegexTrie中根据命令取出一个Runnable
            Runnable command = mCommandTrie.retrieve(groups, tokens);
            if (command != null) {
	            // 执行这个Runnable的run方法
                executeCmdRunnable(command, groups);
            } else {
                printLine(String.format(
                        "Unable to handle command '%s'.  Enter 'help' for help.", tokens[0]));
            }
            RunUtil.getDefault().sleep(100);
        } while (!mShouldExit);//当输入退出命令时，循环退出
    } catch (Exception e) {
        printLine("Console received an unexpected exception (shown below); shutting down TF.");
        e.printStackTrace();
    } finally {
        mScheduler.shutdown();
        // Make sure that we don't quit with messages still in the buffers
        System.err.flush();
        System.out.flush();
    }
}
```
这里就能一目了然的看出来，前面我们已经介绍了RegexTrie，初始化的时候把支持的命令给装载进去，这里就通过命令行输入的参数去trie中取，如果匹配到，则最后取出的就是一个Runnable对象，去执行它。
后面我们就以`run cts.xml`这个为例，这里虽然我用到了cts.xml，这个是在CTS测试框架才会使用，而这里是基础框架，不过因为主要是用xml文件举例，不会涉及到具体cts相关的内容，只需要把cts.xml当做一个普通的配置文件即可。
前面已经说明了，对于run这个命令主要是将其添加到CommandScheduler的队列中。
``` java
// Run commands
ArgRunnable<CaptureList> runRunCommand = new ArgRunnable<CaptureList>() {
    @Override
    public void run(CaptureList args) {
        // The second argument "command" may also be missing, if the
        // caller used the shortcut.
        int startIdx = 1;
        if (args.get(1).isEmpty()) {
            // Empty array (that is, not even containing an empty string) means that
            // we matched and skipped /(?:singleC|c)ommand/
            startIdx = 2;
        }
        String[] flatArgs = new String[args.size() - startIdx];
        for (int i = startIdx; i < args.size(); i++) {
            flatArgs[i - startIdx] = args.get(i).get(0);
        }
        try {
	        // 将命令添加至CommandSchedler的命令队列，等待调度
            mScheduler.addCommand(flatArgs);
        } catch (ConfigurationException e) {
            printLine("Failed to run command: " + e.toString());
        }
    }
};
```
这里看上去只有一个简单的addCommand，但是需要注意的是此时参数还是我们从命令行输入的参数，也就是说，现在到这个命令被调度并执行，还需要很重要的一步：解析装载，这个也是这个框架完成注入的特别重要的一环。

##4.总结
这篇文件主要介绍了基础框架的启动，作为一个java程序，从main入口开始，初始化全局配置，到后面启动Console读取控制台的输出，进行解析从CommandRegexTrie中取出命令开始执行。

下篇文章重点介绍配置文件的解析与装载。
