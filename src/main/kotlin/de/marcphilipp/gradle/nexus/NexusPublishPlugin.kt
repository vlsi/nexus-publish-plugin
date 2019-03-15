/*
 * Copyright 2019 the original author or authors.
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

package de.marcphilipp.gradle.nexus

import io.codearte.gradle.nexus.NexusStagingExtension
import org.gradle.BuildAdapter
import org.gradle.BuildResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

@Suppress("UnstableApiUsage")
class NexusPublishPlugin : Plugin<Project> {

    companion object {
        const val INITIALIZE_NEXUS_STAGING_REPOSITORY_TASK_NAME = "initializeNexusStagingRepository"
        const val PUBLISH_TO_NEXUS_LIFECYCLE_TASK_NAME = "publishToNexus"
        private val serverUrlToStagingRepoUrl = ConcurrentHashMap<URI, URI>()
    }

    override fun apply(project: Project) {
        project.pluginManager.apply(MavenPublishPlugin::class)

        project.gradle.addBuildListener(object : BuildAdapter() {
            override fun buildFinished(result: BuildResult) {
                serverUrlToStagingRepoUrl.clear()
            }
        })

        val extension = project.extensions.create<NexusPublishExtension>(NexusPublishExtension.NAME, project)
        val publishToNexusTask = project.tasks.register(PUBLISH_TO_NEXUS_LIFECYCLE_TASK_NAME) {
            description = "Publishes all Maven publications produced by this project to Nexus."
            group = PublishingPlugin.PUBLISH_TASK_GROUP
        }
        val initializeTask = project.tasks
                .register<InitializeNexusStagingRepository>(INITIALIZE_NEXUS_STAGING_REPOSITORY_TASK_NAME, project, extension, serverUrlToStagingRepoUrl)

        project.afterEvaluate {
            val nexusRepository = addMavenRepository(project, extension)
            configureTaskDependencies(project, publishToNexusTask, initializeTask, nexusRepository)
        }

        project.rootProject.plugins.withId("io.codearte.nexus-staging") {
            val nexusStagingExtension = project.rootProject.the<NexusStagingExtension>()

            extension.packageGroup.set(project.provider { nexusStagingExtension.packageGroup })
            extension.stagingProfileId.set(project.provider { nexusStagingExtension.stagingProfileId })
            extension.username.set(project.provider { nexusStagingExtension.username })
            extension.password.set(project.provider { nexusStagingExtension.password })
        }
    }

    private fun addMavenRepository(project: Project, extension: NexusPublishExtension): MavenArtifactRepository {
        return project.the<PublishingExtension>().repositories.maven {
            name = extension.repositoryName.get()
            url = getRepoUrl(extension)
            credentials {
                username = extension.username.orNull
                password = extension.password.orNull
            }
        }
    }

    private fun configureTaskDependencies(project: Project, publishToNexusTask: TaskProvider<Task>, initializeTask: TaskProvider<InitializeNexusStagingRepository>, nexusRepository: MavenArtifactRepository) {
        val publishTasks = project.tasks
                .withType<PublishToMavenRepository>()
                .matching { it.repository == nexusRepository }
        publishToNexusTask.configure { dependsOn(publishTasks) }
        // PublishToMavenRepository tasks may not yet have been initialized
        project.afterEvaluate {
            publishTasks.configureEach {
                dependsOn(initializeTask)
                doFirst { logger.info("Uploading to {}", repository.url) }
            }
        }
    }

    private fun getRepoUrl(nexusPublishExtension: NexusPublishExtension): URI {
        return if (shouldUseStaging(nexusPublishExtension)) nexusPublishExtension.serverUrl.get() else nexusPublishExtension.snapshotRepositoryUrl.get()
    }

    private fun shouldUseStaging(nexusPublishExtension: NexusPublishExtension): Boolean {
        return nexusPublishExtension.useStaging.get()
    }
}
