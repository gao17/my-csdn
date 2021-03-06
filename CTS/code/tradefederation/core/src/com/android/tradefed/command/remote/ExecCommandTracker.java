/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tradefed.command.remote;

import com.android.tradefed.command.ICommandScheduler.IScheduledInvocationListener;
import com.android.tradefed.config.Configuration;
import com.android.tradefed.device.FreeDeviceState;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;

import com.google.common.collect.ImmutableMap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

class ExecCommandTracker implements IScheduledInvocationListener {

    private CommandResult.Status mStatus = CommandResult.Status.EXECUTING;
    private String mErrorDetails = null;
    private FreeDeviceState mState = null;
    Map<String, String> mRunMetrics = new HashMap<String, String>();

    @Override
    public void invocationFailed(Throwable cause) {
        // TODO: replace with StreamUtil.getStackTrace
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream bytePrintStream = new PrintStream(outputStream);
        cause.printStackTrace(bytePrintStream);
        mErrorDetails = outputStream.toString();
    }

    /**
     * {@inheritDoc}
     * @deprecated use {@link #invocationComplete(IInvocationContext, Map)} instead.
     */
    @Deprecated
    @Override
    public void invocationComplete(ITestDevice device, FreeDeviceState deviceState) {
        IInvocationContext stubMeta = new InvocationContext();
        stubMeta.addAllocatedDevice(Configuration.DEVICE_NAME, device);
        // Stub metadata for compatibility
        Map<ITestDevice, FreeDeviceState> state = new HashMap<>();
        state.put(device, deviceState);
        invocationComplete(stubMeta, state);
    }

    @Override
    public void invocationComplete(IInvocationContext metadata,
            Map<ITestDevice, FreeDeviceState> devicesStates) {
        // FIXME: CommandTracker should handle multiple device states.
        mState = devicesStates.get(metadata.getDevices().get(0));
        if (mErrorDetails != null) {
            mStatus = CommandResult.Status.INVOCATION_ERROR;
        } else {
            mStatus = CommandResult.Status.INVOCATION_SUCCESS;
        }
    }

    @Override
    public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
        mRunMetrics.putAll(runMetrics);
    }

    /**
     * Returns the current state as a {@link com.android.tradefed.command.remote.CommandResult}.
     */
    CommandResult getCommandResult() {
        return new CommandResult(mStatus, mErrorDetails, mState,
                new ImmutableMap.Builder<String, String>()
                    .putAll(mRunMetrics).build());
    }
}
