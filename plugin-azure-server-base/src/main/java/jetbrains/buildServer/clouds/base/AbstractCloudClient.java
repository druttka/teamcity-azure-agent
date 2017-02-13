/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.clouds.base;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.clouds.*;
import jetbrains.buildServer.clouds.base.beans.CloudImageDetails;
import jetbrains.buildServer.clouds.base.connector.CloudApiConnector;
import jetbrains.buildServer.clouds.base.connector.CloudAsyncTaskExecutor;
import jetbrains.buildServer.clouds.base.errors.CloudErrorMap;
import jetbrains.buildServer.clouds.base.errors.DefaultErrorMessageUpdater;
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo;
import jetbrains.buildServer.clouds.base.errors.UpdatableCloudErrorProvider;
import jetbrains.buildServer.clouds.base.tasks.UpdateInstancesTask;
import jetbrains.buildServer.serverSide.AgentDescription;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Sergey.Pak
 *         Date: 7/22/2014
 *         Time: 1:49 PM
 */
public abstract class AbstractCloudClient<G extends AbstractCloudInstance<T>, T extends AbstractCloudImage<G, D>, D extends CloudImageDetails>
        implements CloudClientEx, UpdatableCloudErrorProvider {

    private static final Logger LOG = Logger.getInstance(AbstractCloudClient.class.getName());
    protected final Map<String, T> myImageMap;
    private final UpdatableCloudErrorProvider myErrorProvider;
    protected final CloudAsyncTaskExecutor myAsyncTaskExecutor;
    protected CloudApiConnector<T, G> myApiConnector;
    private final CloudClientParameters myParameters;
    private final AtomicBoolean myIsInitialized = new AtomicBoolean(false);

    public AbstractCloudClient(@NotNull final CloudClientParameters params,
                               @NotNull final CloudApiConnector<T, G> apiConnector) {
        myParameters = params;
        myAsyncTaskExecutor = new CloudAsyncTaskExecutor(params.getProfileDescription());
        myImageMap = new HashMap<>();
        myErrorProvider = new CloudErrorMap(new DefaultErrorMessageUpdater(), "Unable to initialize cloud client. See details");
        myApiConnector = apiConnector;
    }

    public boolean isInitialized() {
        return myIsInitialized.get();
    }


    public void dispose() {
        myAsyncTaskExecutor.dispose();
    }

    @NotNull
    public G startNewInstance(@NotNull final CloudImage baseImage, @NotNull final CloudInstanceUserData tag) throws QuotaException {
        final T image = (T) baseImage;
        return image.startNewInstance(tag);
    }

    public void restartInstance(@NotNull final CloudInstance baseInstance) {
        final G instance = (G) baseInstance;
        instance.getImage().restartInstance(instance);
    }

    public void terminateInstance(@NotNull final CloudInstance baseInstance) {
        final G instance = (G) baseInstance;
        instance.getImage().terminateInstance(instance);
    }

    public boolean canStartNewInstance(@NotNull final CloudImage baseImage) {
        final T image = (T) baseImage;
        return image.canStartNewInstance();
    }

    Future<?> populateImagesDataAsync(@NotNull final Collection<D> imageDetails) {
        return populateImagesDataAsync(imageDetails, 60 * 1000);
    }

    private Future<?> populateImagesDataAsync(@NotNull final Collection<D> imageDetails, final long updateDelayMs) {
        return myAsyncTaskExecutor.submit("Populate images data", new Runnable() {
            public void run() {
                try {
                    populateImagesData(imageDetails, updateDelayMs, updateDelayMs);
                } finally {
                    myIsInitialized.set(true);
                    LOG.info("Cloud profile '" + myParameters.getProfileDescription() + "' initialized");
                }
            }
        });
    }

    private void populateImagesData(@NotNull final Collection<D> imageDetails, long initialDelaySec, long delayMs) {
        for (D details : imageDetails) {
            T image = checkAndCreateImage(details);
            myImageMap.put(image.getName(), image);
        }
        final UpdateInstancesTask<G, T, ?> updateInstancesTask = createUpdateInstancesTask();
        updateInstancesTask.run();
        myAsyncTaskExecutor.scheduleWithFixedDelay("Update instances", updateInstancesTask, initialDelaySec, delayMs, TimeUnit.MILLISECONDS);
    }

    protected abstract T checkAndCreateImage(@NotNull final D imageDetails);

    protected abstract UpdateInstancesTask<G, T, ?> createUpdateInstancesTask();

    @Nullable
    public abstract G findInstanceByAgent(@NotNull final AgentDescription agent);

    @Nullable
    public T findImageById(@NotNull final String imageId) throws CloudException {
        return myImageMap.get(imageId);
    }

    @NotNull
    public Collection<T> getImages() throws CloudException {
        return Collections.unmodifiableCollection(myImageMap.values());
    }

    public void updateErrors(final TypedCloudErrorInfo... errors) {
        myErrorProvider.updateErrors(errors);
    }

    @Nullable
    public CloudErrorInfo getErrorInfo() {
        return myErrorProvider.getErrorInfo();
    }
}
