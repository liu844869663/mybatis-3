package org.apache.ibatis.reflection;

import java.util.HashMap;

import org.apache.ibatis.domain.misc.CustomBeanWrapperFactory;
import org.apache.ibatis.domain.misc.RichType;
import org.junit.jupiter.api.Test;

public class BeanWrapperTest {

	@Test
	public void test01() {
		RichType object = new RichType();

		object.setRichType(new RichType());
		object.getRichType().setRichMap(new HashMap<String, Object>());
		object.getRichType().getRichMap().put("nihao", "123");

		MetaObject meta = MetaObject.forObject(object, SystemMetaObject.DEFAULT_OBJECT_FACTORY,
				new CustomBeanWrapperFactory(), new DefaultReflectorFactory());
		Class<?> clazz = meta.getObjectWrapper().getGetterType("richType.richMap.nihao");
		System.out.println(clazz);
	}

}
