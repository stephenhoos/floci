package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContainerLaunchSecurityTest {
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final DockerClient docker = mock(DockerClient.class);
    private final ImageCacheService images = mock(ImageCacheService.class);
    private final ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, images,
            mock(ContainerDetector.class), mock(PortAllocator.class), config);

    @Test
    void privilegedLaunchIsRefusedBeforeAnyImagePullOrDockerCall() {
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        ContainerSpec spec = builder.newContainer("attacker/image:latest").withPrivileged(true).build();
        AwsException error = assertThrows(AwsException.class, () -> manager.create(spec));
        assertEquals(403, error.getHttpStatus());
        verifyNoInteractions(images, docker);
    }

    @Test
    void unapprovedImageIsRefusedBeforeAnyImagePullOrDockerCall() {
        when(config.security().allowedContainerImages()).thenReturn(Optional.of(List.of("approved/app@sha256:abc")));
        assertThrows(AwsException.class, () -> manager.create(new ContainerSpec("attacker/app:latest")));
        verifyNoInteractions(images, docker);
    }

    @Test
    void exactApprovedReferenceCanBeResolved() {
        when(config.security().allowedContainerImages()).thenReturn(Optional.of(List.of("approved/app:1")));
        ImageCacheService.LaunchImage image = new ImageCacheService.LaunchImage("sha256:123", null);
        EmulatorConfig.EcsServiceConfig.ImagePullBehavior behavior = EmulatorConfig.EcsServiceConfig.ImagePullBehavior.DEFAULT;
        when(images.resolveForLaunch("approved/app:1", behavior)).thenReturn(image);
        assertEquals(image, manager.resolveImageForLaunch("approved/app:1", behavior));
        verify(images).resolveForLaunch("approved/app:1", behavior);
    }

    @Test
    void explicitPrivilegeConsentAllowsAnApprovedImage() {
        when(config.security().allowPrivilegedContainers()).thenReturn(true);
        when(config.security().allowedContainerImages()).thenReturn(Optional.of(List.of("approved/app:1")));
        when(images.ensureImageExists("approved/app:1")).thenReturn("sha256:approved");
        mockCreate("sha256:approved");
        ContainerBuilder builder = new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        assertEquals("container-id", manager.create(builder.newContainer("approved/app:1").withPrivileged(true).build()));
    }

    @Test
    void imageIdResolvedFromApprovedReferenceRemainsLaunchable() {
        exactApprovedReferenceCanBeResolved();
        when(images.ensureImageExists("sha256:123")).thenReturn("sha256:123");
        mockCreate("sha256:123");
        assertEquals("container-id", manager.create(new ContainerSpec("sha256:123")));
    }

    private void mockCreate(String image) {
        CreateContainerCmd command = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(docker.createContainerCmd(image)).thenReturn(command);
        when(command.exec()).thenReturn(response);
        when(response.getId()).thenReturn("container-id");
    }
}
