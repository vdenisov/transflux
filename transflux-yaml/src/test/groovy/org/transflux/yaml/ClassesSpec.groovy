/*
 *
 *  * Copyright 2025 Victor Denisov
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.transflux.yaml

import org.transflux.core.ComponentFactory
import org.transflux.core.action.Action
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification
import spock.lang.TempDir

import javax.tools.ToolProvider
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate

class ClassesSpec extends Specification {

    @TempDir
    Path compiled

    def 'loads and instantiates a class named at a position'() {
        given:
        def classes = new Classes(getClass().classLoader, ComponentFactory.reflective())
        def map = map("class: ${Step.name}\n")

        expect:
        classes.requiredClass(map, 'class', Action) == Step
        classes.instantiate(map, 'class', Action) instanceof Step
    }

    def 'refuses #what at the line naming the class'() {
        given:
        def classes = new Classes(getClass().classLoader, factory)

        when:
        classes.instantiate(map("\nclass: ${name}\n"), 'class', Action)

        then:
        def e = thrown(DefinitionLoadException)
        e.line() == 2
        e.column() == 8
        e.problem() == problem

        where:
        what                      | name            | factory                                || problem
        'an unknown class'        | 'com.nope.Step' | ComponentFactory.reflective()          || 'class com.nope.Step cannot be loaded'
        'the wrong type'          | String.name     | ComponentFactory.reflective()          || "class java.lang.String is not a subtype of ${Action.name}"
        'no usable constructor'   | NoDefault.name  | ComponentFactory.reflective()          || "Cannot instantiate ${NoDefault.name}: it declares no no-argument constructor"
        'a factory failure'       | Step.name       | { throw new TransfluxValidationException('no bean') } as ComponentFactory || 'no bean'
        'a null from the factory' | Step.name       | { null } as ComponentFactory           || "the component factory returned null for class ${Step.name}"
        'the wrong instance'      | Step.name       | { 'text' } as ComponentFactory         || "the component factory returned a java.lang.String for class ${Step.name}"
        'a host factory failure'  | Step.name       | { throw new IllegalStateException('no bean') } as ComponentFactory || "the component factory failed for class ${Step.name}: java.lang.IllegalStateException"
        'a failing initialiser'   | FailingStep.name | ComponentFactory.reflective()         || "Cannot instantiate ${FailingStep.name}: its static initialisation failed with java.lang.IllegalStateException"
    }

    def 'classes are loaded through the class loader given'() {
        given: 'a loader that sees only the JDK'
        def classes = new Classes(new URLClassLoader(new URL[0], (ClassLoader) null), ComponentFactory.reflective())

        when:
        classes.requiredClass(map("class: ${Step.name}\n"), 'class', null)

        then:
        thrown(DefinitionLoadException)
    }

    def 'a class is loaded without being initialised'() {
        given:
        def classes = new Classes(getClass().classLoader, ComponentFactory.reflective())

        when:
        classes.requiredClass(map("class: ${ExplodingInitializer.name}\n"), 'class', null)

        then:
        noExceptionThrown()
    }

    def 'an absent optional class is null, a present one is loaded'() {
        given:
        def classes = new Classes(getClass().classLoader, ComponentFactory.reflective())
        def map = map("class: ${Step.name}\n")

        expect:
        classes.optionalClass(map, 'context', null) == null
        classes.optionalClass(map, 'class', Action) == Step
    }

    def 'type arguments the position refuses are reported at the line naming the class, before instantiating'() {
        given:
        def created = 0
        def classes = new Classes(getClass().classLoader, { created++; new Step() } as ComponentFactory)

        when:
        classes.instantiate(map("\nclass: ${Step.name}\n"), 'class', Action,
            TypeArguments.Expected.superOf(Object), TypeArguments.Expected.exactly(String))

        then:
        def e = thrown(DefinitionLoadException)
        e.line() == 2
        e.problem() == "class ${Step.name} declares Action's C as java.lang.Object, where this position needs java.lang.String"
        created == 0
    }

    def 'a factory may return a proxy usable at the position rather than an instance of the class'() {
        given:
        def proxy = Proxy.newProxyInstance(getClass().classLoader, [Action] as Class[],
            { p, method, args -> null } as InvocationHandler)
        def classes = new Classes(getClass().classLoader, { proxy } as ComponentFactory)

        expect:
        classes.instantiate(map("class: ${Step.name}\n"), 'class', Action).is(proxy)
    }

    def 'a class referring to an absent class through #where is reported at the line naming it'() {
        given:
        def classes = new Classes(loaderWithoutMissing(), ComponentFactory.reflective())

        when:
        classes.instantiate(map("\nclass: pkg.${name}\n"), 'class', Predicate, expected as TypeArguments.Expected[])

        then:
        def e = thrown(DefinitionLoadException)
        e.line() == 2
        e.problem() == problem

        where:
        where             | name          | expected                                 || problem
        'a type argument' | 'Typed'       | [TypeArguments.Expected.exactly(Object)] || 'class pkg.Typed refers to a class that cannot be loaded (java.lang.TypeNotPresentException: Type pkg.Missing not present)'
        'a constructor'   | 'Constructed' | []                                       || 'Cannot instantiate pkg.Constructed: a constructor mentions a class that cannot be loaded (pkg/Missing)'
    }

    /**
     * Compiles two classes mentioning {@code pkg.Missing} - one as its type argument, one as a
     * constructor parameter - and loads them with {@code pkg.Missing} itself deleted.
     *
     * @return a loader that sees the two classes but not the one they mention
     */
    private ClassLoader loaderWithoutMissing() {
        def sources = [
            Missing    : 'public class Missing {}',
            Typed      : 'public class Typed implements java.util.function.Predicate<Missing> {'
                + ' public boolean test(Missing m) { return true; } }',
            Constructed: 'public class Constructed implements java.util.function.Predicate<Object> {'
                + ' public Constructed() {} public Constructed(Missing m) {}'
                + ' public boolean test(Object o) { return true; } }']
        def files = sources.collect { name, body ->
            Files.writeString(Files.createDirectories(compiled.resolve('src/pkg')).resolve("${name}.java"),
                "package pkg; ${body}").toString()
        }
        def out = compiled.resolve('classes')
        assert ToolProvider.systemJavaCompiler.run(null, null, null,
            (['--release', '17', '-d', out.toString()] + files) as String[]) == 0
        Files.delete(out.resolve('pkg/Missing.class'))
        return new URLClassLoader([out.toUri().toURL()] as URL[], getClass().classLoader)
    }

    private static NodeMap map(String text) {
        def document = Document.parse([], 'doc.yml', null, new StringReader(text))
        return NodeMap.of(document, document.root(), null, 'the document')
    }

    static class Step implements Action<Object, Object> {
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
        }
    }

    static class NoDefault implements Action<Object, Object> {
        NoDefault(String ignored) {
        }

        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
        }
    }

    static class FailingStep implements Action<Object, Object> {
        static {
            if (true) {
                throw new IllegalStateException('initialised')
            }
        }

        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
        }
    }

    static class ExplodingInitializer {
        static {
            if (true) {
                throw new IllegalStateException('initialised')
            }
        }
    }
}
