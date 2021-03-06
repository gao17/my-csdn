/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.testtype.suite;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IAbiReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IRuntimeHintProvider;
import com.android.tradefed.testtype.IShardableTest;
import com.android.tradefed.testtype.ITestCollector;
import com.android.tradefed.testtype.ITestFilterReceiver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** A test Stub that can be used to fake some runs for suite's testing. */
public class TestSuiteStub
        implements IRemoteTest,
                IAbiReceiver,
                IRuntimeHintProvider,
                ITestCollector,
                ITestFilterReceiver,
                IShardableTest {

    @Option(name = "module")
    private String mModule;

    @Option(name = "foo")
    protected String mFoo;

    @Option(name = "blah")
    protected String mBlah;

    @Option(name = "report-test")
    protected boolean mReportTest = false;

    @Option(name = "run-complete")
    protected boolean mIsComplete = true;

    @Option(name = "test-fail")
    protected boolean mDoesOneTestFail = true;

    @Option(name = "internal-retry")
    protected boolean mRetry = false;

    @Option(name = "throw-device-not-available")
    protected boolean mThrow = false;

    protected List<TestIdentifier> mShardedTestToRun;
    protected Integer mShardIndex = null;

    /** Tests attempt. */
    private void testAttempt(ITestInvocationListener listener) throws DeviceNotAvailableException {
        listener.testRunStarted(mModule, 3);
        TestIdentifier tid = new TestIdentifier("TestStub", "test1");
        listener.testStarted(tid);
        listener.testEnded(tid, Collections.emptyMap());

        if (mIsComplete) {
            // possibly skip this one to create some not_executed case.
            TestIdentifier tid2 = new TestIdentifier("TestStub", "test2");
            listener.testStarted(tid2);
            if (mThrow) {
                throw new DeviceNotAvailableException();
            }
            listener.testEnded(tid2, Collections.emptyMap());
        }

        TestIdentifier tid3 = new TestIdentifier("TestStub", "test3");
        listener.testStarted(tid3);
        if (mDoesOneTestFail) {
            listener.testFailed(tid3, "ouch this is bad.");
        }
        listener.testEnded(tid3, Collections.emptyMap());

        listener.testRunEnded(0, Collections.emptyMap());
    }

    /** {@inheritDoc} */
    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        if (mReportTest) {
            if (mShardedTestToRun == null) {
                if (!mRetry) {
                    testAttempt(listener);
                } else {
                    // We fake an internal retry by calling testRunStart/Ended again.
                    listener.testRunStarted(mModule, 3);
                    listener.testRunEnded(0, Collections.emptyMap());
                    testAttempt(listener);
                }
            } else {
                // Run the shard
                if (mDoesOneTestFail) {
                    listener.testRunStarted(mModule, mShardedTestToRun.size() + 1);
                } else {
                    listener.testRunStarted(mModule, mShardedTestToRun.size());
                }

                if (mIsComplete) {
                    for (TestIdentifier tid : mShardedTestToRun) {
                        listener.testStarted(tid);
                        listener.testEnded(tid, Collections.emptyMap());
                    }
                } else {
                    TestIdentifier tid = mShardedTestToRun.get(0);
                    listener.testStarted(tid);
                    listener.testEnded(tid, Collections.emptyMap());
                }

                if (mDoesOneTestFail) {
                    TestIdentifier tid = new TestIdentifier("TestStub", "failed" + mShardIndex);
                    listener.testStarted(tid);
                    listener.testFailed(tid, "shard failed this one.");
                    listener.testEnded(tid, Collections.emptyMap());
                }
                listener.testRunEnded(0, Collections.emptyMap());
            }
        }
    }

    @Override
    public Collection<IRemoteTest> split(int shardCountHint) {
        if (mShardedTestToRun == null) {
            return null;
        }
        Collection<IRemoteTest> listTest = new ArrayList<>();
        for (TestIdentifier id : mShardedTestToRun) {
            TestSuiteStub stub = new TestSuiteStub();
            OptionCopier.copyOptionsNoThrow(this, stub);
            stub.mShardedTestToRun = new ArrayList<>();
            stub.mShardedTestToRun.add(id);
            listTest.add(stub);
        }
        return listTest;
    }

    @Override
    public void setAbi(IAbi abi) {
        // Do nothing
    }

    @Override
    public IAbi getAbi() {
        return null;
    }

    @Override
    public long getRuntimeHint() {
        return 1L;
    }

    @Override
    public void setCollectTestsOnly(boolean shouldCollectTest) {
        // Do nothing
    }

    @Override
    public void addIncludeFilter(String filter) {}

    @Override
    public void addAllIncludeFilters(Set<String> filters) {}

    @Override
    public void addExcludeFilter(String filter) {}

    @Override
    public void addAllExcludeFilters(Set<String> filters) {}
}
