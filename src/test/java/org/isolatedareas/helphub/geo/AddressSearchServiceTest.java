package org.isolatedareas.helphub.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

class AddressSearchServiceTest {
    @Test
    void withoutAnyKeyItIsUnavailableAndFindsNothing() {
        var search = new AddressSearchService(RestClient.builder(), new ObjectMapper(), mock(StringRedisTemplate.class),
            "", "", "", "");
        assertThat(search.available()).isFalse();
        assertThat(search.search("青浦区青湖路 88 弄")).isEmpty();
    }

    @Test
    void eitherProviderKeyMakesItAvailable() {
        var redis = mock(StringRedisTemplate.class);
        assertThat(new AddressSearchService(RestClient.builder(), new ObjectMapper(), redis, "tencent", "", "", "").available()).isTrue();
        assertThat(new AddressSearchService(RestClient.builder(), new ObjectMapper(), redis, "", "", "amap", "").available()).isTrue();
    }

    @Test
    void amapPointsAreLongitudeFirst() {
        assertThat(AddressSearchService.amapPoint(new TextNode("121.111100,31.166500"))).containsExactly(31.1665, 121.1111);
        assertThat(AddressSearchService.amapPoint(new ObjectMapper().createArrayNode())).isNull();
    }

    @Test
    void signsRawValuesButSendsThemEncoded() {
        Map<String, String> params = new TreeMap<>(Map.of("keyword", "青湖路 88弄", "key", "K", "region", "上海市"));
        assertThat(AddressSearchService.query(params, false)).isEqualTo("key=K&keyword=青湖路 88弄&region=上海市");
        assertThat(AddressSearchService.query(params, true))
            .isEqualTo("key=K&keyword=%E9%9D%92%E6%B9%96%E8%B7%AF%2088%E5%BC%84&region=%E4%B8%8A%E6%B5%B7%E5%B8%82");
    }
}
