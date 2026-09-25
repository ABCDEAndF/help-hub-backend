package org.isolatedareas.helphub.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The assistant extracts request ids deterministically because small models miss
 * colloquial phrasings such as "17号单子".
 */
class RequestIdExtractionTest {

    private String extract(String message) {
        try {
            Method m = AssistantService.class.getDeclaredMethod("extractRequestId", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsIdsFromFormalPhrasings() {
        assertThat(extract("帮我查询申请 1 的状态")).isEqualTo("1");
        assertThat(extract("查一下 #17 现在到哪一步了")).isEqualTo("17");
        assertThat(extract("check request 42 status")).isEqualTo("42");
    }

    @Test
    void readsIdsFromColloquialPhrasings() {
        assertThat(extract("我那个17号单子咋样了")).isEqualTo("17");
        assertThat(extract("我的8号订单到哪了")).isEqualTo("8");
        assertThat(extract("工单 123 处理完了吗")).isEqualTo("123");
    }

    @Test
    void returnsNothingWhenNoIdIsPresent() {
        assertThat(extract("我的申请进度怎么样了")).isNull();
        assertThat(extract("现在有哪些物资？")).isNull();
        assertThat(extract("你好")).isNull();
    }
}
