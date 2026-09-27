package org.isolatedareas.helphub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Spring can only build a component from its sole constructor, or from the one marked
 * {@code @Autowired}; anything else fails at startup ("No default constructor found"), which no
 * unit test would otherwise notice because none of them start the application context.
 */
class ComponentConstructorTest {
    @Test
    void everyComponentHasOneConstructorSpringCanUse() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        List<String> ambiguous = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("org.isolatedareas.helphub")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            boolean noArgs = Arrays.stream(constructors).anyMatch(constructor -> constructor.getParameterCount() == 0);
            long marked = Arrays.stream(constructors).filter(constructor -> constructor.isAnnotationPresent(Autowired.class)).count();
            if (constructors.length > 1 && marked != 1 && !noArgs) ambiguous.add(type.getName());
        }
        assertThat(ambiguous).as("components Spring cannot construct").isEmpty();
    }
}
