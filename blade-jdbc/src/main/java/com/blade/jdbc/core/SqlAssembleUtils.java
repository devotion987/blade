package com.blade.jdbc.core;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blade.jdbc.annotation.Column;
import com.blade.jdbc.exceptions.AssistantException;
import com.blade.jdbc.model.SqlOpts;
import com.blade.kit.CollectionKit;
import com.blade.kit.StringKit;
import com.blade.kit.reflect.FieldCallback;
import com.blade.kit.reflect.ReflectKit;

public class SqlAssembleUtils {

	/** 日志对象 */
	private static final Logger LOG = LoggerFactory.getLogger(SqlAssembleUtils.class);

	/**
	 * 获取实体类对象
	 */
	public static Class<?> getEntityClass(Object entity, Take take) {
		return entity == null ? take.getEntityClass() : entity.getClass();
	}

	/**
	 * 构建insert语句
	 *
	 * @param entity
	 *            实体映射对象
	 * @param take
	 *            the take
	 * @param nameHandler
	 *            名称转换处理器
	 * @return bound sql
	 */
	public static BoundSql buildInsertSql(Object entity, Take take, NameHandler nameHandler) {

		Class<?> entityClass = getEntityClass(entity, take);
		List<AutoField> autoFields = (take != null ? take.getAutoFields() : CollectionKit.newArrayList());

		List<AutoField> entityAutoField = getEntityAutoField(entity, SqlOpts.UPDATE);

		// 添加到后面
		autoFields.addAll(entityAutoField);

		String tableName = nameHandler.getTableName(entityClass);
		String pkName = nameHandler.getPKName(entityClass);

		StringBuilder sql = new StringBuilder(SqlOpts.INSERT_INTO.getValue());
		List<Object> params = CollectionKit.newArrayList();
		sql.append(tableName);

		sql.append("(");
		StringBuilder args = new StringBuilder();
		args.append("(");

		for (AutoField autoField : autoFields) {

			if (autoField.getType() != SqlOpts.UPDATE && autoField.getType() != SqlOpts.PK_VALUE_NAME) {
				continue;
			}
			String columnName = nameHandler.getColumnName(autoField.getName());
			Object value = autoField.getValues()[0];

			sql.append(columnName);
			// 如果是主键，且是主键的值名称
			if (pkName.equalsIgnoreCase(columnName) && autoField.getType() == SqlOpts.PK_VALUE_NAME) {
				// 参数直接append，传参方式会把值当成字符串造成无法调用序列的问题
				args.append(value);
			} else {
				args.append(" ?");
				params.add(value);
			}
			sql.append(",");
			args.append(",");
		}
		sql.deleteCharAt(sql.length() - 1);
		args.deleteCharAt(args.length() - 1);
		args.append(")");
		sql.append(")");
		sql.append(" values ");
		sql.append(args);
		return new BoundSql(sql.toString(), pkName, params);
	}

	/**
	 * 构建更新sql
	 */
	public static BoundSql buildUpdateSql(Object entity, Take take, NameHandler nameHandler) {

		Class<?> entityClass = getEntityClass(entity, take);
		List<AutoField> autoFields = (take != null ? take.getAutoFields() : CollectionKit.newArrayList());

		List<AutoField> entityAutoField = getEntityAutoField(entity, SqlOpts.UPDATE);

		// 添加到后面，防止or等操作被覆盖
		autoFields.addAll(entityAutoField);

		StringBuilder sql = new StringBuilder();
		List<Object> params = CollectionKit.newArrayList();
		String tableName = nameHandler.getTableName(entityClass);
		String primaryName = nameHandler.getPKName(entityClass);

		sql.append("update ").append(tableName).append(" set ");

		Object primaryValue = null;

		Iterator<AutoField> iterator = autoFields.iterator();
		while (iterator.hasNext()) {

			AutoField autoField = iterator.next();

			if (SqlOpts.UPDATE != autoField.getType()) {
				continue;
			}

			String columnName = nameHandler.getColumnName(autoField.getName());

			// 如果是主键
			if (primaryName.equalsIgnoreCase(columnName)) {

				Object[] values = autoField.getValues();

				if (null == values || values.length == 0 || StringKit.isBlank(values[0].toString())) {
					throw new AssistantException("primary key not is null");
				}
				primaryValue = values[0];
			}

			if (!primaryName.equalsIgnoreCase(columnName)) {
				sql.append(columnName).append(" ").append(autoField.getFieldOperator()).append(" ");
				if (CollectionKit.isEmpty(autoField.getValues()) || autoField.getValues()[0] == null) {
					sql.append("null");
				} else {
					sql.append(" ?");
					params.add(autoField.getValues()[0]);
				}
				sql.append(",");

				// 移除掉操作过的元素
				iterator.remove();
			}

		}

		sql.deleteCharAt(sql.length() - 1);
		sql.append(" where ");

		if (primaryValue != null) {
			sql.append(primaryName).append(" = ?");
			params.add(primaryValue);
		} else {
			BoundSql boundSql = SqlAssembleUtils.builderWhereSql(autoFields, nameHandler);
			sql.append(boundSql.getSql());
			params.addAll(boundSql.getParams());
		}
		return new BoundSql(sql.toString(), primaryName, params);
	}

	/**
	 * 获取所有的操作属性，entity非null字段将被转换到列表
	 */
	private static List<AutoField> getEntityAutoField(Object entity, SqlOpts operateType) {

		// 汇总的所有操作属性
		List<AutoField> autoFieldList = CollectionKit.newArrayList();

		if (entity == null) {
			return autoFieldList;
		}

		// 获取属性信息
		Class<? extends Object> clazz = entity.getClass();
		FieldCallback callback = new FieldCallback() {

			@Override
			public void callBack(Field field) throws Exception {
				Column column = field.getAnnotation(Column.class);
				if (null == column) {
					String fieldName = field.getName();
					buildAutoField(entity, operateType, autoFieldList, field, fieldName);
				} else {
					if (!column.ignore()) {
						String name = column.name();
						String fieldName = StringKit.isEmpty(name) ? name : field.getName();
						buildAutoField(entity, operateType, autoFieldList, field, fieldName);
					}
				}
			}

			private void buildAutoField(Object entity, SqlOpts operateType, List<AutoField> autoFieldList, Field field,
					String fieldName) throws Exception {
				AutoField autoField = new AutoField();
				Object value = ReflectKit.getFieldValue(entity, field);
				if (null != value) {
					autoField.setName(fieldName);
					autoField.setSqlOperator(SqlOpts.AND.getValue());
					autoField.setFieldOperator(SqlOpts.EQ.getValue());
					autoField.setValues(new Object[] { value });
					autoField.setType(operateType);
					autoFieldList.add(autoField);
				}
			}
		};
		try {
			callback.callBackField(clazz);
		} catch (Exception e) {
			e.printStackTrace();
			LOG.error("获取属性失败：" + e);
		}

		return autoFieldList;
	}

	/**
	 * 构建where条件sql
	 */
	private static BoundSql builderWhereSql(List<AutoField> autoFields, NameHandler nameHandler) {

		StringBuilder sql = new StringBuilder();
		List<Object> params = CollectionKit.newArrayList();
		Iterator<AutoField> iterator = autoFields.iterator();
		while (iterator.hasNext()) {
			AutoField autoField = iterator.next();
			if (SqlOpts.WHERE != autoField.getType()) {
				continue;
			}
			// 操作过，移除
			iterator.remove();
			if (sql.length() > 0) {
				sql.append(" ").append(autoField.getSqlOperator()).append(" ");
			}
			String columnName = nameHandler.getColumnName(autoField.getName());
			Object[] values = autoField.getValues();

			String fieldOperator = autoField.getFieldOperator();
			if (SqlOpts.IN.getValue().equalsIgnoreCase(fieldOperator)
					|| SqlOpts.NOT_IN.getValue().equalsIgnoreCase(fieldOperator)) {

				// in，not in的情况
				sql.append(columnName).append(" ").append(fieldOperator).append(" ");
				sql.append("(");
				for (int j = 0; j < values.length; j++) {
					sql.append(" ?");
					params.add(values[j]);
					if (j != values.length - 1) {
						sql.append(",");
					}
				}
				sql.append(")");
			} else if (values == null) {
				// null 值
				sql.append(columnName).append(" ").append(fieldOperator).append(" null");
			} else if (values.length == 1) {
				// 一个值 =
				sql.append(columnName).append(" ").append(fieldOperator).append(" ?");
				params.add(values[0]);
			} else {
				// 多个值，or的情况
				sql.append("(");
				for (int j = 0; j < values.length; j++) {
					sql.append(columnName).append(" ").append(fieldOperator).append(" ?");
					params.add(values[j]);
					if (j != values.length - 1) {
						sql.append(" or ");
					}
				}
				sql.append(")");
			}
		}
		return new BoundSql(sql.toString(), null, params);
	}

	/**
	 * 构建根据主键删除sql
	 */
	public static BoundSql buildDeleteSql(Class<?> clazz, Serializable id, NameHandler nameHandler) {

		List<Object> params = CollectionKit.newArrayList();
		params.add(id);
		String tableName = nameHandler.getTableName(clazz);
		String primaryName = nameHandler.getPKName(clazz);
		StringBuilder sql = new StringBuilder("delete from ").append(tableName).append(" where ").append(primaryName)
				.append(" = ?");
		return new BoundSql(sql.toString(), primaryName, params);
	}

	/**
	 * 构建删除sql
	 */
	public static BoundSql buildDeleteSql(Object entity, Take take, NameHandler nameHandler) {

		Class<?> entityClass = getEntityClass(entity, take);
		List<AutoField> autoFields = (take != null ? take.getAutoFields() : CollectionKit.newArrayList());

		List<AutoField> entityAutoField = getEntityAutoField(entity, SqlOpts.WHERE);

		autoFields.addAll(entityAutoField);

		String tableName = nameHandler.getTableName(entityClass);
		String primaryName = nameHandler.getPKName(entityClass);

		StringBuilder sql = new StringBuilder("delete from ").append(tableName).append(" where ");
		BoundSql boundSql = SqlAssembleUtils.builderWhereSql(autoFields, nameHandler);
		boundSql.setSql(sql.append(boundSql.getSql()).toString());
		boundSql.setPrimaryKey(primaryName);

		return boundSql;
	}

	/**
	 * 构建根据id查询sql
	 */
	public static BoundSql buildByIdSql(Class<?> clazz, Serializable pk, Take take, NameHandler nameHandler) {

		Class<?> entityClass = (clazz == null ? take.getEntityClass() : clazz);
		String tableName = nameHandler.getTableName(entityClass);
		String primaryName = nameHandler.getPKName(entityClass);
		String columns = SqlAssembleUtils.buildColumnSql(entityClass, nameHandler);
		StringBuilder sql = new StringBuilder("select ").append(columns).append(" from ").append(tableName)
				.append(" where ").append(primaryName).append(" = ?");
		List<Object> params = CollectionKit.newArrayList();
		params.add(pk);

		return new BoundSql(sql.toString(), primaryName, params);
	}

	/**
	 * 按设置的条件构建查询sql
	 */
	public static BoundSql buildQuerySql(Object entity, Take take, NameHandler nameHandler) {

		Class<?> entityClass = getEntityClass(entity, take);

		List<AutoField> autoFields = (take != null ? take.getAutoFields() : CollectionKit.newArrayList());

		String tableName = nameHandler.getTableName(entityClass);
		String primaryName = nameHandler.getPKName(entityClass);

		List<AutoField> entityAutoField = getEntityAutoField(entity, SqlOpts.WHERE);
		autoFields.addAll(entityAutoField);

		String columns = SqlAssembleUtils.buildColumnSql(entityClass, nameHandler);
		StringBuilder querySql = new StringBuilder("select ").append(columns).append(" from ");
		querySql.append(tableName);

		List<Object> params = Collections.emptyList();
		if (null != take && take.hasWhere() || autoFields.size() > 0) {
			querySql.append(" where ");

			BoundSql boundSql = SqlAssembleUtils.builderWhereSql(autoFields, nameHandler);
			params = boundSql.getParams();
			querySql.append(boundSql.getSql());
		}

		return new BoundSql(querySql.toString(), primaryName, params);
	}

	/**
	 * 构建列表查询sql
	 */
	public static BoundSql buildListSql(Object entity, Take take, NameHandler nameHandler) {

		BoundSql boundSql = SqlAssembleUtils.buildQuerySql(entity, take, nameHandler);

		StringBuilder sb = new StringBuilder(" order by ");
		if (take != null) {
			for (AutoField autoField : take.getOrderByFields()) {
				sb.append(nameHandler.getColumnName(autoField.getName())).append(" ")
						.append(autoField.getFieldOperator()).append(",");
			}

			if (sb.length() > 10) {
				sb.deleteCharAt(sb.length() - 1);
			}
		}

		if (sb.length() < 11) {
			sb.append(boundSql.getPrimaryKey()).append(" desc");
		}
		boundSql.setSql(boundSql.getSql() + sb.toString());
		return boundSql;
	}

	/**
	 * 构建记录数查询sql
	 */
	public static BoundSql buildCountSql(Object entity, Take take, NameHandler nameHandler) {

		Class<?> entityClass = getEntityClass(entity, take);
		List<AutoField> autoFields = (take != null ? take.getAutoFields() : CollectionKit.newArrayList());

		List<AutoField> entityAutoField = getEntityAutoField(entity, SqlOpts.WHERE);
		autoFields.addAll(entityAutoField);

		String tableName = nameHandler.getTableName(entityClass);
		StringBuilder countSql = new StringBuilder("select count(0) from ");
		countSql.append(tableName);

		List<Object> params = Collections.emptyList();
		if (!CollectionKit.isEmpty(autoFields)) {
			countSql.append(" where ");
			BoundSql boundSql = builderWhereSql(autoFields, nameHandler);
			countSql.append(boundSql.getSql());
			params = boundSql.getParams();
		}

		return new BoundSql(countSql.toString(), null, params);
	}

	/**
	 * 构建查询的列sql
	 */
	public static String buildColumnSql(Class<?> clazz, NameHandler nameHandler) {

		StringBuilder columns = new StringBuilder();

		FieldCallback callBack = new FieldCallback() {

			@Override
			public void callBack(Field field) throws Exception {
				Column column = field.getAnnotation(Column.class);
				if (null == column) {
					String fieldName = field.getName();
					String columnName = nameHandler.getColumnName(fieldName);
					columns.append(columnName);
					columns.append(",");
				} else {
					if (!column.ignore()) {
						String name = column.name();
						String columnName = StringKit.isEmpty(name) ? name : nameHandler.getColumnName(field.getName());
						columns.append(columnName);
						columns.append(",");
					}
				}
			}

		};
		try {
			callBack.callBackField(clazz);
		} catch (Exception e) {
			e.printStackTrace();
			LOG.error("获取属性失败：" + e);
		}
		columns.deleteCharAt(columns.length() - 1);
		return columns.toString();
	}

	/**
	 * 构建排序条件
	 */
	public static String buildOrderBy(String sort, NameHandler nameHandler, String... properties) {

		StringBuilder sb = new StringBuilder();
		for (String property : properties) {
			String columnName = nameHandler.getColumnName(property);
			sb.append(columnName);
			sb.append(" ");
			sb.append(sort);
			sb.append(",");
		}
		sb.deleteCharAt(sb.length() - 1);
		return sb.toString();
	}

	/**
	 * 构建查询oracle xmltype类型的sql
	 */
	public static BoundSql buildOracleXmlTypeSql(Class<?> clazz, String fieldName, Long id, NameHandler nameHandler) {
		String tableName = nameHandler.getTableName(clazz);
		String primaryName = nameHandler.getPKName(clazz);
		String columnName = nameHandler.getColumnName(fieldName);

		String sql_tmp = "select t.%s.getclobval() xmlFile from %s t where t.%s = ?";
		String sql = String.format(sql_tmp, columnName, tableName, primaryName);
		List<Object> params = CollectionKit.newArrayList();
		params.add(id);
		return new BoundSql(sql, primaryName, params);
	}

}
