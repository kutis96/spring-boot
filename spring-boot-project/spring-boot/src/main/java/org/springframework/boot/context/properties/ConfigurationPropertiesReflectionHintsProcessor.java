/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.properties;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.beans.BeanInfoFactory;
import org.springframework.beans.ExtendedBeanInfoFactory;
import org.springframework.boot.context.properties.bind.BindConstructorProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ReflectionUtils;

/**
 * Registers a given type on {@link ReflectionHints} for binding purposes, discovering any
 * nested type it may expose via a property.
 *
 * @author Andy Wilkinson
 * @author Moritz Halbritter
 * @author Sebastien Deleuze
 * @since 3.0.0
 */
public final class ConfigurationPropertiesReflectionHintsProcessor {

	private static final BeanInfoFactory beanInfoFactory = new ExtendedBeanInfoFactory();

	private final Class<?> type;

	private final Constructor<?> bindConstructor;

	private final BeanInfo beanInfo;

	private final Set<Class<?>> seen;

	private ConfigurationPropertiesReflectionHintsProcessor(Class<?> type, Constructor<?> bindConstructor,
			Set<Class<?>> seen) {
		this.type = type;
		this.bindConstructor = bindConstructor;
		this.beanInfo = getBeanInfo(type);
		this.seen = seen;
	}

	/**
	 * Registers a given type on {@link ReflectionHints} for binding purposes, discovering
	 * any nested type it may expose via a property.
	 * @param type type to process
	 * @param reflectionHints {@link ReflectionHints} to register the types on
	 */
	public static void processConfigurationProperties(Class<?> type, ReflectionHints reflectionHints) {
		new ConfigurationPropertiesReflectionHintsProcessor(type, getBindConstructor(type, false), new HashSet<>())
				.process(reflectionHints);
	}

	private void processNestedType(Class<?> type, ReflectionHints reflectionHints) {
		processNestedType(type, getBindConstructor(type, true), reflectionHints);
	}

	private void processNestedType(Class<?> type, Constructor<?> bindConstructor, ReflectionHints reflectionHints) {
		new ConfigurationPropertiesReflectionHintsProcessor(type, bindConstructor, this.seen).process(reflectionHints);
	}

	private static Constructor<?> getBindConstructor(Class<?> type, boolean nestedType) {
		Bindable<?> bindable = Bindable.of(type);
		return BindConstructorProvider.DEFAULT.getBindConstructor(bindable, nestedType);
	}

	private void process(ReflectionHints reflectionHints) {
		if (this.seen.contains(this.type)) {
			return;
		}
		this.seen.add(this.type);
		handleConstructor(reflectionHints);
		if (this.bindConstructor != null) {
			handleValueObjectProperties(reflectionHints);
		}
		else if (this.beanInfo != null) {
			handleJavaBeanProperties(reflectionHints);
		}
	}

	private void handleConstructor(ReflectionHints reflectionHints) {
		if (this.bindConstructor != null) {
			reflectionHints.registerConstructor(this.bindConstructor, ExecutableMode.INVOKE);
			return;
		}
		Arrays.stream(this.type.getDeclaredConstructors()).filter(this::hasNoParameters).findFirst()
				.ifPresent((constructor) -> reflectionHints.registerConstructor(constructor, ExecutableMode.INVOKE));
	}

	private boolean hasNoParameters(Constructor<?> candidate) {
		return candidate.getParameterCount() == 0;
	}

	private void handleValueObjectProperties(ReflectionHints reflectionHints) {
		for (int i = 0; i < this.bindConstructor.getParameterCount(); i++) {
			String propertyName = this.bindConstructor.getParameters()[i].getName();
			ResolvableType propertyType = ResolvableType.forConstructorParameter(this.bindConstructor, i);
			handleProperty(reflectionHints, propertyName, propertyType);
		}
	}

	private void handleJavaBeanProperties(ReflectionHints reflectionHints) {
		for (PropertyDescriptor propertyDescriptor : this.beanInfo.getPropertyDescriptors()) {
			Method writeMethod = propertyDescriptor.getWriteMethod();
			if (writeMethod != null) {
				reflectionHints.registerMethod(writeMethod, ExecutableMode.INVOKE);
			}
			Method readMethod = propertyDescriptor.getReadMethod();
			if (readMethod != null) {
				ResolvableType propertyType = ResolvableType.forMethodReturnType(readMethod, this.type);
				String propertyName = propertyDescriptor.getName();
				if (isSetterMandatory(propertyName, propertyType) && writeMethod == null) {
					continue;
				}
				handleProperty(reflectionHints, propertyName, propertyType);
				reflectionHints.registerMethod(readMethod, ExecutableMode.INVOKE);
			}
		}
	}

	private boolean isSetterMandatory(String propertyName, ResolvableType propertyType) {
		Class<?> propertyClass = propertyType.resolve();
		if (propertyClass == null) {
			return true;
		}
		if (isContainer(propertyType)) {
			return false;
		}
		return !isNestedType(propertyName, propertyClass);
	}

	private void handleProperty(ReflectionHints reflectionHints, String propertyName, ResolvableType propertyType) {
		Class<?> propertyClass = propertyType.resolve();
		if (propertyClass == null) {
			return;
		}
		if (propertyClass.equals(this.type)) {
			return; // Prevent infinite recursion
		}
		Class<?> componentType = getComponentClass(propertyType);
		if (componentType != null) {
			// Can be a list of simple types
			if (!isJavaType(componentType)) {
				processNestedType(componentType, reflectionHints);
			}
		}
		else if (isNestedType(propertyName, propertyClass)) {
			processNestedType(propertyClass, reflectionHints);
		}
	}

	private Class<?> getComponentClass(ResolvableType type) {
		ResolvableType componentType = getComponentType(type);
		if (componentType == null) {
			return null;
		}
		if (isContainer(componentType)) {
			// Resolve nested generics like Map<String, List<SomeType>>
			return getComponentClass(componentType);
		}
		return componentType.toClass();
	}

	private ResolvableType getComponentType(ResolvableType type) {
		if (type.isArray()) {
			return type.getComponentType();
		}
		if (isCollection(type)) {
			return type.asCollection().getGeneric();
		}
		if (isMap(type)) {
			return type.asMap().getGeneric(1);
		}
		return null;
	}

	private boolean isContainer(ResolvableType type) {
		return type.isArray() || isCollection(type) || isMap(type);
	}

	private boolean isCollection(ResolvableType type) {
		return Collection.class.isAssignableFrom(type.toClass());
	}

	private boolean isMap(ResolvableType type) {
		return Map.class.isAssignableFrom(type.toClass());
	}

	/**
	 * Specify whether the specified property refer to a nested type. A nested type
	 * represents a sub-namespace that need to be fully resolved. Nested types are either
	 * inner classes or annotated with {@link NestedConfigurationProperty}.
	 * @param propertyName the name of the property
	 * @param propertyType the type of the property
	 * @return whether the specified {@code propertyType} is a nested type
	 */
	private boolean isNestedType(String propertyName, Class<?> propertyType) {
		if (this.type.equals(propertyType.getDeclaringClass())) {
			return true;
		}
		else {
			Field field = ReflectionUtils.findField(this.type, propertyName);
			return field != null && MergedAnnotations.from(field).isPresent(NestedConfigurationProperty.class);
		}
	}

	private boolean isJavaType(Class<?> candidate) {
		return candidate.getPackageName().startsWith("java.");
	}

	private static BeanInfo getBeanInfo(Class<?> beanType) {
		try {
			BeanInfo beanInfo = beanInfoFactory.getBeanInfo(beanType);
			if (beanInfo != null) {
				return beanInfo;
			}
			return Introspector.getBeanInfo(beanType, Introspector.IGNORE_ALL_BEANINFO);
		}
		catch (IntrospectionException ex) {
			return null;
		}
	}

}
