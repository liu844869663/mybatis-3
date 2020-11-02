/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.scripting.xmltags;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

import ognl.OgnlContext;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

/**
 * 解析动态SQL语句时的上下文
 *
 * @author Clinton Begin
 */
public class DynamicContext {

  /**
   * 入参保存在 ContextMap 中的 Key
   *
   * {@link #bindings}
   */
  public static final String PARAMETER_OBJECT_KEY = "_parameter";
  /**
   * 数据库编号保存在 ContextMap 中的 Key
   *
   * {@link #bindings}
   */
  public static final String DATABASE_ID_KEY = "_databaseId";

  static {
    // <1.2> 设置 OGNL 的属性访问器
    OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
  }

  /**
   * 上下文的参数集合，包含附加参数（通过`<bind />`标签生成的，或者`<foreach />`标签中的集合的元素等等）
   */
  private final ContextMap bindings;
  /**
   * 生成后的 SQL
   */
  private final StringJoiner sqlBuilder = new StringJoiner(" ");
  /**
   * 唯一编号。在 {@link org.apache.ibatis.scripting.xmltags.ForEachSqlNode} 使用
   */
  private int uniqueNumber = 0;

  public DynamicContext(Configuration configuration, Object parameterObject) {
    // <1> 初始化 bindings 参数
    if (parameterObject != null && !(parameterObject instanceof Map)) {
      // 构建入参的 MetaObject 对象
      MetaObject metaObject = configuration.newMetaObject(parameterObject);
      // 入参类型是否有对应的类型处理器
      boolean existsTypeHandler = configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
      bindings = new ContextMap(metaObject, existsTypeHandler);
    } else {
      bindings = new ContextMap(null, false);
    }
    // <2> 添加 bindings 的默认值
    bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
    bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
  }

  public Map<String, Object> getBindings() {
    return bindings;
  }

  public void bind(String name, Object value) {
    bindings.put(name, value);
  }

  public void appendSql(String sql) {
    sqlBuilder.add(sql);
  }

  public String getSql() {
    return sqlBuilder.toString().trim();
  }

  public int getUniqueNumber() {
    return uniqueNumber++;
  }

  static class ContextMap extends HashMap<String, Object> {
    private static final long serialVersionUID = 2977601501966151582L;
    /**
     * parameter 对应的 MetaObject 对象
     */
    private final MetaObject parameterMetaObject;
    /**
     * 是否有对应的类型处理器
     */
    private final boolean fallbackParameterObject;

    public ContextMap(MetaObject parameterMetaObject, boolean fallbackParameterObject) {
      this.parameterMetaObject = parameterMetaObject;
      this.fallbackParameterObject = fallbackParameterObject;
    }

    @Override
    public Object get(Object key) {
      String strKey = (String) key;
      if (super.containsKey(strKey)) {
        return super.get(strKey);
      }

      if (parameterMetaObject == null) {
        return null;
      }

      if (fallbackParameterObject && !parameterMetaObject.hasGetter(strKey)) {
        return parameterMetaObject.getOriginalObject();
      } else {
        // issue #61 do not modify the context when reading
        return parameterMetaObject.getValue(strKey);
      }
    }
  }

  static class ContextAccessor implements PropertyAccessor {

    @Override
    public Object getProperty(Map context, Object target, Object name) {
      Map map = (Map) target;

      // 优先从 ContextMap 中，获得属性
      Object result = map.get(name);
      if (map.containsKey(name) || result != null) {
        return result;
      }

      // <x> 如果没有，则从 PARAMETER_OBJECT_KEY 对应的 Map 中，获得属性
      Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
      if (parameterObject instanceof Map) {
        return ((Map) parameterObject).get(name);
      }

      return null;
    }

    @Override
    public void setProperty(Map context, Object target, Object name, Object value) {
      Map<Object, Object> map = (Map<Object, Object>) target;
      map.put(name, value);
    }

    @Override
    public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }

    @Override
    public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }
  }
}
