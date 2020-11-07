/**
 *    Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.executor.keygen;

import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;

/**
 * @author Clinton Begin
 * @author Jeff Butler
 */
public class SelectKeyGenerator implements KeyGenerator {

  /**
   * <selectKey /> 解析成 MappedStatement 对象的 id 的后缀
   * 例如 <selectKey /> 所在的 MappedStatement 的 id 为 namespace.test
   * 那么它的 id 就是 namespace.test!selectKey
   */
  public static final String SELECT_KEY_SUFFIX = "!selectKey";
  /**
   * 是否在 SQL 执行后执行
   */
  private final boolean executeBefore;
  /**
   * <selectKey /> 解析成 MappedStatement 对象
   */
  private final MappedStatement keyStatement;

  public SelectKeyGenerator(MappedStatement keyStatement, boolean executeBefore) {
    this.executeBefore = executeBefore;
    this.keyStatement = keyStatement;
  }

  @Override
  public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    if (executeBefore) { // 如果是在 SQL 执行之前进行生成 key
      processGeneratedKeys(executor, ms, parameter);
    }
  }

  @Override
  public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    if (!executeBefore) { // 如果是在 SQL 执行之后进行生成 key
      processGeneratedKeys(executor, ms, parameter);
    }
  }

  private void processGeneratedKeys(Executor executor, MappedStatement ms, Object parameter) {
    try {
      if (parameter != null && keyStatement != null && keyStatement.getKeyProperties() != null) {
        // <1> 获取 keyProperty 配置
        String[] keyProperties = keyStatement.getKeyProperties();
        final Configuration configuration = ms.getConfiguration();
        // <2> 创建入参 MetaObject 对象
        final MetaObject metaParam = configuration.newMetaObject(parameter);
        if (keyProperties != null) {
          // Do not close keyExecutor.
          // The transaction will be closed by parent executor.
          // <3> 创建一个 SimpleExecutor 执行器
          Executor keyExecutor = configuration.newExecutor(executor.getTransaction(), ExecutorType.SIMPLE);
          // <4> 执行数据库查询
          List<Object> values = keyExecutor.query(keyStatement, parameter, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
          if (values.size() == 0) {
            throw new ExecutorException("SelectKey returned no data.");
          } else if (values.size() > 1) {
            throw new ExecutorException("SelectKey returned more than one value.");
          } else {
            // <5> 为数据库查询的到数据创建 MetaObject 对象
            MetaObject metaResult = configuration.newMetaObject(values.get(0));
            if (keyProperties.length == 1) { // <6.1> 单个属性
              if (metaResult.hasGetter(keyProperties[0])) {
                // 往入参中设置该属性为从数据库查询到的数据
                setValue(metaParam, keyProperties[0], metaResult.getValue(keyProperties[0]));
              } else {
                // no getter for the property - maybe just a single value object
                // so try that
                setValue(metaParam, keyProperties[0], values.get(0));
              }
            } else { //<6.2> 多个属性
              handleMultipleProperties(keyProperties, metaParam, metaResult);
            }
          }
        }
      }
    } catch (ExecutorException e) {
      throw e;
    } catch (Exception e) {
      throw new ExecutorException("Error selecting key or setting result to parameter object. Cause: " + e, e);
    }
  }

  private void handleMultipleProperties(String[] keyProperties, MetaObject metaParam, MetaObject metaResult) {
    // 获取 keyColumn 配置
    String[] keyColumns = keyStatement.getKeyColumns();

    if (keyColumns == null || keyColumns.length == 0) { // 没有配置列名则直接去属性名
      // no key columns specified, just use the property names
      for (String keyProperty : keyProperties) {
        // 往入参中设置该属性为从数据库查询到的数据，从查询到的结果中取属性名
        setValue(metaParam, keyProperty, metaResult.getValue(keyProperty));
      }
    } else {
      if (keyColumns.length != keyProperties.length) { // 列名和属性名的个数不一致则抛出异常
        throw new ExecutorException("If SelectKey has key columns, the number must match the number of key properties.");
      }
      for (int i = 0; i < keyProperties.length; i++) {
        // 往入参中设置该属性为从数据库查询到的数据，从查询到的结果中取列名
        setValue(metaParam, keyProperties[i], metaResult.getValue(keyColumns[i]));
      }
    }
  }

  private void setValue(MetaObject metaParam, String property, Object value) {
    if (metaParam.hasSetter(property)) {
      metaParam.setValue(property, value);
    } else {
      throw new ExecutorException("No setter found for the keyProperty '" + property + "' in " + metaParam.getOriginalObject().getClass().getName() + ".");
    }
  }
}
