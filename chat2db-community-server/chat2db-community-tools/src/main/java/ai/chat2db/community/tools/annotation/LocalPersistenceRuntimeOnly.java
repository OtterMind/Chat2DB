package ai.chat2db.community.tools.annotation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${chat2db.runtime.mode:pro}'.equalsIgnoreCase('community')"
        + " || '${chat2db.runtime.mode:pro}'.equalsIgnoreCase('local')")
public @interface LocalPersistenceRuntimeOnly {
}
