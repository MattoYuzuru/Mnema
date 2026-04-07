package app.mnema.core.review.service;

import app.mnema.core.review.entity.SrAlgorithmEntity;
import app.mnema.core.review.repository.SrAlgorithmRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlgorithmDefaultConfigCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void getDefaultConfigCachesRepositoryResult() {
        SrAlgorithmRepository repository = mock(SrAlgorithmRepository.class);
        SrAlgorithmEntity entity = new SrAlgorithmEntity();
        entity.setDefaultConfig(MAPPER.createObjectNode().put("requestRetention", 0.9));
        when(repository.findById("fsrs_v6")).thenReturn(Optional.of(entity));

        AlgorithmDefaultConfigCache cache = new AlgorithmDefaultConfigCache(repository);

        assertThat(cache.getDefaultConfig("fsrs_v6").path("requestRetention").asDouble()).isEqualTo(0.9);
        assertThat(cache.getDefaultConfig("fsrs_v6").path("requestRetention").asDouble()).isEqualTo(0.9);
        verify(repository, times(1)).findById("fsrs_v6");
    }

    @Test
    void getDefaultConfigCachesNullForMissingAlgorithm() {
        SrAlgorithmRepository repository = mock(SrAlgorithmRepository.class);
        when(repository.findById("missing")).thenReturn(Optional.empty());

        AlgorithmDefaultConfigCache cache = new AlgorithmDefaultConfigCache(repository);

        assertThat(cache.getDefaultConfig("missing")).isNull();
        assertThat(cache.getDefaultConfig("missing")).isNull();
        verify(repository, times(1)).findById("missing");
    }
}
