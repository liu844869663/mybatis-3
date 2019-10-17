/**
 *    Copyright 2009-2015 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.datasource.unpooled;

import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * @author Clinton Begin
 */
public class UnpooledDataSourceFactory implements DataSourceFactory {

	private static final String DRIVER_PROPERTY_PREFIX = "driver.";
	private static final int DRIVER_PROPERTY_PREFIX_LENGTH = DRIVER_PROPERTY_PREFIX.length();

	protected DataSource dataSource;

	public UnpooledDataSourceFactory() {
		this.dataSource = new UnpooledDataSource();
	}

	@Override
	public void setProperties(Properties properties) {
		Properties driverProperties = new Properties();
		// 创建 dataSource 对应的 MetaObject 对象，其中是BeanWrapper
		MetaObject metaDataSource = SystemMetaObject.forObject(dataSource);
		// 遍历 properties 属性，初始化到 driverProperties 和 MetaObject 中
		for (Object key : properties.keySet()) {
			String propertyName = (String) key;
			// 初始化到 driverProperties 中
			if (propertyName.startsWith(DRIVER_PROPERTY_PREFIX)) {
				String value = properties.getProperty(propertyName);
				driverProperties.setProperty(propertyName.substring(DRIVER_PROPERTY_PREFIX_LENGTH), value);
			// 如果该属性在UnpooledDataSource中有setter方法，则初始化到 MetaObject 中
			} else if (metaDataSource.hasSetter(propertyName)) {
				String value = (String) properties.get(propertyName);
				Object convertedValue = convertValue(metaDataSource, propertyName, value);
				// 将数据设置到UnpooledDataSource中去
				metaDataSource.setValue(propertyName, convertedValue);
			} else {
				throw new DataSourceException("Unknown DataSource property: " + propertyName);
			}
		}
		if (driverProperties.size() > 0) {
			// 设置 driverProperties 到 MetaObject 中
			metaDataSource.setValue("driverProperties", driverProperties);
		}
	}

	@Override
	public DataSource getDataSource() {
		return dataSource;
	}

	private Object convertValue(MetaObject metaDataSource, String propertyName, String value) {
		Object convertedValue = value;
		// 获得该属性的 setting 方法的参数类型
		Class<?> targetType = metaDataSource.getSetterType(propertyName);
		// 转化
		if (targetType == Integer.class || targetType == int.class) {
			convertedValue = Integer.valueOf(value);
		} else if (targetType == Long.class || targetType == long.class) {
			convertedValue = Long.valueOf(value);
		} else if (targetType == Boolean.class || targetType == boolean.class) {
			convertedValue = Boolean.valueOf(value);
		}
		return convertedValue;
	}

}
