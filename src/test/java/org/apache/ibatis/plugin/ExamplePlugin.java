package org.apache.ibatis.plugin;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Field;
import java.util.Properties;

/**
 * example for mybatis plugin
 */
@Intercepts({
  @Signature(
    type = Executor.class,
    method = "query",
    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
  )
})
public class ExamplePlugin implements Interceptor {

  // Executor的查询方法：
  // public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler)

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object[] args = invocation.getArgs();
    RowBounds rowBounds = (RowBounds) args[2];
    if (rowBounds == RowBounds.DEFAULT) { // 无需分页
      return invocation.proceed();
    }
    /*
     * 将query方法的 RowBounds 入参设置为空对象
     * 也就是关闭 MyBatis 内部实现的分页（逻辑分页，在拿到查询结果后再进行分页的，而不是物理分页）
     */
    args[2] = RowBounds.DEFAULT;

    MappedStatement mappedStatement = (MappedStatement) args[0];
    BoundSql boundSql = mappedStatement.getBoundSql(args[1]);

    // 获取 SQL 语句，拼接 limit 语句
    String sql = boundSql.getSql();
    String limit = String.format("LIMIT %d,%d", rowBounds.getOffset(), rowBounds.getLimit());
    sql = sql + " " + limit;

    // 创建一个 StaticSqlSource 对象
    SqlSource sqlSource = new StaticSqlSource(mappedStatement.getConfiguration(), sql, boundSql.getParameterMappings());

    // 通过反射获取并设置 MappedStatement 的 sqlSource 字段
    Field field = MappedStatement.class.getDeclaredField("sqlSource");
    field.setAccessible(true);
    field.set(mappedStatement, sqlSource);

    // 执行被拦截方法
    return invocation.proceed();
  }

  @Override
  public Object plugin(Object target) {
    // default impl
    return Plugin.wrap(target, this);
  }

  @Override
  public void setProperties(Properties properties) {
    // default nop
  }
}
