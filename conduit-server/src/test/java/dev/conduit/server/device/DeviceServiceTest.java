package dev.conduit.server.device;

import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.product.ProductService;
import dev.conduit.server.security.KeyGenerator;
import dev.conduit.server.security.SecretHasher;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceTest {

    @Test
    void deleteSucceedsAndStillCleansOtherKeysWhenRedisFails() {
        DeviceMapper devices = mock(DeviceMapper.class);
        DeviceIdentityCache identityCache = mock(DeviceIdentityCache.class);
        LatestValueStore latest = mock(LatestValueStore.class);
        PresenceStore presence = mock(PresenceStore.class);
        DeviceService service = new DeviceService(devices, mock(ProductMapper.class), mock(ProductService.class),
                mock(KeyGenerator.class), mock(SecretHasher.class), identityCache, latest, presence, Clock.systemUTC());

        Device d = new Device();
        d.setId(7L);
        d.setDeviceKey("dk1");
        when(devices.selectOne(any())).thenReturn(d);
        doThrow(new RedisConnectionFailureException("down")).when(latest).delete("dk1");

        assertThatCode(() -> service.delete("dk1")).doesNotThrowAnyException();

        var order = inOrder(devices, identityCache, latest, presence);
        order.verify(devices).deleteById(7L);
        order.verify(identityCache).evict("dk1");
        order.verify(latest).delete("dk1");
        order.verify(presence).delete("dk1");
    }
}
