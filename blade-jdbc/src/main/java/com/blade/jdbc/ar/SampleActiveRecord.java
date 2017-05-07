package com.blade.jdbc.ar;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.sql2o.Connection;
import org.sql2o.Sql2o;

import com.blade.jdbc.ActiveRecord;
import com.blade.jdbc.Base;
import com.blade.jdbc.core.BoundSql;
import com.blade.jdbc.core.DefaultNameHandler;
import com.blade.jdbc.core.NameHandler;
import com.blade.jdbc.core.SqlAssembleUtils;
import com.blade.jdbc.core.Take;
import com.blade.jdbc.model.PageRow;
import com.blade.jdbc.model.Paginator;
import com.blade.jdbc.tx.JdbcTx;
import com.blade.jdbc.tx.Tx;
import com.blade.jdbc.utils.NameUtils;
import com.blade.jdbc.utils.Utils;
import com.blade.kit.CollectionKit;
import com.blade.kit.StringKit;

/**
 * ActiveRecord default implement
 */
public class SampleActiveRecord implements ActiveRecord {

	/** sql2o 对象 */
	private Sql2o sql2o;

	/** 名称处理器，为空按默认执行 */
	private NameHandler nameHandler;

	/** 数据库方言 */
	private String dialect = "mysql";

	private Object[] EMPTY = new Object[] {};

	public SampleActiveRecord() {
	}

	public SampleActiveRecord(String url, String user, String pass) {
		this.sql2o = Base.open(url, user, pass);
	}

	public SampleActiveRecord(DataSource dataSource) {
		this.sql2o = Base.open(dataSource);
	}

	public SampleActiveRecord(Sql2o sql2o) {
		this.sql2o = sql2o;
	}

	/**
	 * 插入数据
	 *
	 * @param entity
	 *            the entity
	 * @param take
	 *            the take
	 * @return long long
	 */
	@SuppressWarnings("unchecked")
	private <T extends Serializable> T insert(Object entity, Take take) {
		Class<?> entityClass = SqlAssembleUtils.getEntityClass(entity, take);
		NameHandler handler = this.getNameHandler();
		String pkValue = handler.getPKValue(entityClass, this.dialect);
		if (StringKit.isNotBlank(pkValue)) {
			String primaryName = handler.getPKName(entityClass);
			if (take == null) {
				take = Take.create(entityClass);
			}
			take.setPKValueName(NameUtils.getCamelName(primaryName), pkValue);
		}

		final BoundSql boundSql = SqlAssembleUtils.buildInsertSql(entity, take, handler);

		String sql = boundSql.getSql();
		Object[] params = boundSql.getParams().toArray();
		JdbcTx jdbcTx = Tx.jdbcTx();
		if (null != jdbcTx) {
			Connection con = jdbcTx.getConnection();
			try {
				return (T) con.createQuery(sql).withParams(params).executeUpdate().getKey();
			} catch (Exception e) {
				Tx.rollback();
				return null;
			}
		} else {
			try (Connection con = sql2o.beginTransaction()) {
				return (T) con.createQuery(sql).withParams(params).executeUpdate().commit(true).getKey();
			}
		}
	}

	@Override
	public <T extends Serializable> T insert(Object entity) {
		return this.insert(entity, null);
	}

	@Override
	public <T extends Serializable> T insert(Take take) {
		return this.insert(null, take);
	}

	@Override
	public void save(Object entity) {
		final BoundSql boundSql = SqlAssembleUtils.buildInsertSql(entity, null, this.getNameHandler());
		String sql = boundSql.getSql();

		executeAtomic(sql, boundSql.getParams().toArray());
	}

	@Override
	public void save(Take take) {
		final BoundSql boundSql = SqlAssembleUtils.buildInsertSql(null, take, this.getNameHandler());
		String sql = boundSql.getSql();
		executeAtomic(sql, boundSql.getParams().toArray());
	}

	@Override
	public <T extends Serializable> T saveOrUpdate(Object entity) {
		if (null != entity) {
			int count = this.count(entity);
			if (count == 0) {
				return this.insert(entity);
			}
			this.update(entity);
		}
		return null;
	}

	@Override
	public <T extends Serializable> T saveOrUpdate(Take take) {
		if (null != take) {
			int count = this.count(take);
			if (count == 0) {
				return this.insert(take);
			}
			this.update(take);
		}
		return null;
	}

	@Override
	public int update(Take take) {
		BoundSql boundSql = SqlAssembleUtils.buildUpdateSql(null, take, this.getNameHandler());
		String sql = boundSql.getSql();
		return executeAtomic(sql, boundSql.getParams().toArray());
	}

	@Override
	public int update(Object entity) {
		BoundSql boundSql = SqlAssembleUtils.buildUpdateSql(entity, null, this.getNameHandler());
		String sql = boundSql.getSql();
		return executeAtomic(sql, boundSql.getParams().toArray());
	}

	private int executeAtomic(String sql, Object[] params) {
		JdbcTx jdbcTx = Tx.jdbcTx();
		if (null != jdbcTx) {
			Connection con = jdbcTx.getConnection();
			try {
				return con.createQuery(sql).withParams(params).executeUpdate().getResult();
			} catch (Exception e) {
				Tx.rollback();
				return -1;
			}
		} else {
			try (Connection con = sql2o.beginTransaction()) {
				return con.createQuery(sql).withParams(params).executeUpdate().commit(true).getResult();
			}
		}
	}

	@Override
	public int delete(Take take) {
		BoundSql boundSql = SqlAssembleUtils.buildDeleteSql(null, take, this.getNameHandler());
		String sql = boundSql.getSql();
		return executeAtomic(sql, boundSql.getParams().toArray());
	}

	@Override
	public int delete(Object entity) {
		BoundSql boundSql = SqlAssembleUtils.buildDeleteSql(entity, null, this.getNameHandler());
		String sql = boundSql.getSql();
		return executeAtomic(sql, boundSql.getParams().toArray());
	}

	@Override
	public int delete(Class<?> clazz, Serializable id) {
		BoundSql boundSql = SqlAssembleUtils.buildDeleteSql(clazz, id, this.getNameHandler());
		String sql = boundSql.getSql();
		return executeAtomic(sql, boundSql.getParams().toArray());
	}

	@Override
	public int deleteAll(Class<?> clazz) {
		String tableName = this.getNameHandler().getTableName(clazz);
		String sql = "TRUNCATE TABLE " + tableName;
		return executeAtomic(sql, null);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> List<T> list(Take take) {
		BoundSql boundSql = SqlAssembleUtils.buildListSql(null, take, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			List<?> list = con.createQuery(sql).withParams(boundSql.getParams().toArray())
					.executeAndFetch(take.getEntityClass());
			return null != list ? (List<T>) list : CollectionKit.newArrayList();
		}
	}

	@Override
	public <T extends Serializable> List<T> list(Class<T> type, String sql, Object... args) {
		sql = dealSQL(sql);
		args = dealArgs(args);
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(args).executeAndFetch(type);
		}
	}

	private String dealSQL(String sql) {
		int pindex = 1;
		while (sql.contains(" ?")) {
			sql = sql.replaceFirst(" \\?", " :p" + pindex++);
		}
		return sql;
	}

	private Object[] dealArgs(Object... args) {
		if (null == args)
			return EMPTY;
		return args;
	}

	@Override
	public <T extends Serializable> List<T> list(Class<T> type, String sql, PageRow pageRow, Object... args) {
		if (null == args) {
			args = EMPTY;
		}
		sql = dealSQL(sql);
		sql = Utils.getPageSql(sql, dialect, pageRow);
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(args).executeAndFetch(type);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> List<T> list(T entity) {
		BoundSql boundSql = SqlAssembleUtils.buildListSql(entity, null, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			List<?> list = con.createQuery(sql).withParams(boundSql.getParams().toArray())
					.executeAndFetch(entity.getClass());
			return null != list ? (List<T>) list : CollectionKit.newArrayList();
		}
	}

	@Override
	public List<Map<String, Object>> listMap(String sql, Object... args) {
		sql = dealSQL(sql);
		args = dealArgs(args);
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(args).executeAndFetchTable().asList();
		}
	}

	@Override
	public List<Map<String, Object>> listMap(String sql, PageRow pageRow, Object... args) {
		sql = dealSQL(sql);
		sql = Utils.getPageSql(sql, dialect, pageRow);
		args = dealArgs(args);
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(args).executeAndFetchTable().asList();
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> List<T> list(T entity, Take take) {
		BoundSql boundSql = SqlAssembleUtils.buildListSql(entity, take, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			List<?> list = con.createQuery(sql).withParams(boundSql.getParams().toArray())
					.executeAndFetch(entity.getClass());
			return null != list ? (List<T>) list : CollectionKit.newArrayList();
		}
	}

	@Override
	public Map<String, Object> map(String sql, Object... args) {
		sql = dealSQL(sql);
		args = dealArgs(args);
		try (Connection con = sql2o.open()) {
			List<Map<String, Object>> list = con.createQuery(sql).withParams(args).executeAndFetchTable().asList();
			return null != list ? list.get(0) : CollectionKit.newHashMap();
		}
	}

	@Override
	public <T extends Serializable> T one(Class<T> type, String sql, Object... args) {
		sql = dealSQL(sql);
		args = dealArgs(args);
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(args).executeAndFetchFirst(type);
		}
	}

	@Override
	public int count(Object entity, Take take) {
		BoundSql boundSql = SqlAssembleUtils.buildCountSql(entity, take, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(boundSql.getParams().toArray()).executeScalar(Integer.class);
		}
	}

	@Override
	public int count(Object entity) {
		BoundSql boundSql = SqlAssembleUtils.buildCountSql(entity, null, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(boundSql.getParams().toArray()).executeScalar(Integer.class);
		}
	}

	@Override
	public int count(Take take) {
		BoundSql boundSql = SqlAssembleUtils.buildCountSql(null, take, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(boundSql.getParams().toArray()).executeScalar(Integer.class);
		}
	}

	@Override
	public <T extends Serializable> T byId(Class<T> clazz, Serializable pk) {
		BoundSql boundSql = SqlAssembleUtils.buildByIdSql(clazz, pk, null, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			return con.createQuery(sql).withParams(pk).executeAndFetchFirst(clazz);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> T byId(Take take, Serializable pk) {
		BoundSql boundSql = SqlAssembleUtils.buildByIdSql(null, pk, take, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			Object o = con.createQuery(sql).withParams(pk).executeAndFetchFirst(take.getEntityClass());
			return o != null ? (T) o : null;
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> T one(T entity) {
		BoundSql boundSql = SqlAssembleUtils.buildQuerySql(entity, null, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			Object o = con.createQuery(sql).withParams(boundSql.getParams().toArray())
					.executeAndFetchFirst(entity.getClass());
			return o != null ? (T) o : null;
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> T one(Take take) {
		BoundSql boundSql = SqlAssembleUtils.buildQuerySql(null, take, this.getNameHandler());
		String sql = boundSql.getSql();
		try (Connection con = sql2o.open()) {
			Object o = con.createQuery(sql).withParams(boundSql.getParams().toArray())
					.executeAndFetchFirst(take.getEntityClass());
			return o != null ? (T) o : null;
		}
	}

	@Override
	public int execute(String sql, Object... args) {
		sql = dealSQL(sql);
		args = dealArgs(args);
		try (Connection con = sql2o.beginTransaction()) {
			return con.createQuery(sql).withParams(args).executeUpdate().commit(true).getResult();
		}
	}

	@Override
	public <T> Paginator<T> page(T entity, int page, int limit) {
		return this.page(entity, new PageRow(page, limit));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Paginator<T> page(T entity, PageRow pageRow) {
		BoundSql boundSql = SqlAssembleUtils.buildQuerySql(entity, null, this.getNameHandler());
		String countSql = Utils.getCountSql(boundSql.getSql());

		Paginator<T> paginator;

		try (Connection con = sql2o.open()) {
			int total = con.createQuery(countSql).withParams(boundSql.getParams().toArray())
					.executeScalar(Integer.class);

			paginator = new Paginator<>(total, pageRow.getPage(), pageRow.getLimit());

			String sql = Utils.getPageSql(boundSql.getSql(), dialect, pageRow);
			List<?> list = con.createQuery(sql).withParams(boundSql.getParams().toArray())
					.executeAndFetch(entity.getClass());
			paginator.setList((List<T>) list);
		}

		return paginator;
	}

	@Override
	public <T> Paginator<T> page(T entity, int page, int limit, String orderBy) {
		return this.page(entity, new PageRow(page, limit, orderBy));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Paginator<T> page(Take take) {
		BoundSql boundSql = SqlAssembleUtils.buildQuerySql(null, take, this.getNameHandler());
		String countSql = Utils.getCountSql(boundSql.getSql());

		Paginator<T> paginator = null;

		try (Connection con = sql2o.open()) {
			int total = con.createQuery(countSql).withParams(boundSql.getParams().toArray())
					.executeScalar(Integer.class);

			PageRow pageRow = take.getPageRow();
			paginator = new Paginator<>(total, pageRow.getPage(), pageRow.getLimit());

			String sql = Utils.getPageSql(boundSql.getSql(), dialect, pageRow);
			List<?> list = con.createQuery(sql).withParams(boundSql.getParams().toArray())
					.executeAndFetch(take.getEntityClass());
			paginator.setList((List<T>) list);
		}

		return paginator;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Paginator<T> page(Class<T> type, String sql, PageRow pageRow, Object... args) {
		sql = dealSQL(sql);
		args = dealArgs(args);
		String countSql = Utils.getCountSql(sql);
		Paginator<T> paginator = null;
		try (Connection con = sql2o.open()) {
			int total = con.createQuery(countSql).withParams(args).executeScalar(Integer.class);

			paginator = new Paginator<>(total, pageRow.getPage(), pageRow.getLimit());

			sql = Utils.getPageSql(sql, dialect, pageRow);
			List<?> list = con.createQuery(sql).withParams(args).executeAndFetch(type);
			paginator.setList((List<T>) list);
			return paginator;
		}
	}

	/**
	 * 获取名称处理器
	 *
	 * @return
	 */
	protected NameHandler getNameHandler() {
		if (this.nameHandler == null) {
			this.nameHandler = new DefaultNameHandler();
		}
		return this.nameHandler;
	}

	public Sql2o sql2o() {
		return sql2o;
	}

	@Override
	public DataSource datasource() {
		return sql2o.getDataSource();
	}

	@Override
	public java.sql.Connection connection() {
		try {
			return sql2o.getDataSource().getConnection();
		} catch (Exception e) {
			return null;
		}
	}

	public SampleActiveRecord setSql2o(Sql2o sql2o) {
		this.sql2o = sql2o;
		return this;
	}

	public void setNameHandler(NameHandler nameHandler) {
		this.nameHandler = nameHandler;
	}

	public void setDialect(String dialect) {
		this.dialect = dialect;
	}
}
