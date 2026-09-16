package io.agentscope.dataagent.semantic.service;

import io.agentscope.dataagent.semantic.model.SemanticColumn;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import io.agentscope.dataagent.semantic.model.SemanticRelationship;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

@Component
public final class SemanticSqlPlanner {
    private static final Set<String> FUNCTIONS =
            Set.of(
                    "SUM",
                    "COUNT",
                    "AVG",
                    "MIN",
                    "MAX",
                    "ABS",
                    "ROUND",
                    "CEIL",
                    "CEILING",
                    "FLOOR",
                    "COALESCE",
                    "NULLIF",
                    "IFNULL",
                    "IF",
                    "CONCAT",
                    "LOWER",
                    "UPPER",
                    "LENGTH",
                    "CHAR_LENGTH",
                    "TRIM",
                    "SUBSTRING",
                    "SUBSTR",
                    "REPLACE",
                    "DATE",
                    "YEAR",
                    "MONTH",
                    "DAY",
                    "DAYOFMONTH",
                    "HOUR",
                    "WEEK",
                    "WEEKDAY",
                    "QUARTER",
                    "DATE_FORMAT",
                    "DATEDIFF",
                    "STR_TO_DATE",
                    "POWER",
                    "MOD",
                    "DAYOFWEEK",
                    "DAYNAME",
                    "DATE_ADD",
                    "DATE_SUB",
                    "NOW",
                    "CURDATE",
                    "CURRENT_DATE",
                    "TIMESTAMPDIFF",
                    "GREATEST",
                    "LEAST");
    private static final Set<String> BINARY =
            Set.of(
                    "Addition",
                    "Subtraction",
                    "Multiplication",
                    "Division",
                    "IntegerDivision",
                    "Modulo",
                    "EqualsTo",
                    "NotEqualsTo",
                    "GreaterThan",
                    "GreaterThanEquals",
                    "MinorThan",
                    "MinorThanEquals",
                    "AndExpression",
                    "OrExpression",
                    "XorExpression",
                    "LikeExpression");

    public record TableBinding(String schema, String tableName, Set<String> columns) {}

    public record Plan(
            String semanticSql,
            String compiledSql,
            List<String> models,
            List<String> relationships,
            List<String> warnings) {}

    public Plan compile(String sql, SemanticModel model, Map<String, TableBinding> bindings) {
        Select select = parseSelect(sql);
        State state = new State(model, bindings);
        state.select(select, Map.of(), null, 0);
        if (state.used.isEmpty()) throw invalid("查询必须引用语义模型，不能直接访问物理表");
        List<WithItem> ctes = new ArrayList<>();
        for (String name : new ArrayList<>(state.used.keySet())) {
            String body = state.modelSql(name);
            WithItem item = new WithItem();
            item.setAlias(new Alias(quote(state.cteNames.get(name)), false));
            ParenthesedSelect subquery = new ParenthesedSelect();
            subquery.setSelect(parseSelect(body));
            item.setSelect(subquery);
            ctes.add(item);
        }
        if (select.getWithItemsList() != null) ctes.addAll(select.getWithItemsList());
        select.setWithItemsList(ctes);
        String compiled = select.toString();
        if (compiled.length() > 20000) throw invalid("编译 SQL 过长，请减少模型或字段");
        Set<String> leaves =
                new TablesNamesFinder().getTables((net.sf.jsqlparser.statement.Statement) select);
        for (String leaf : leaves) {
            if (!state.physicalTables.contains(key(leaf))) throw invalid("编译结果含未授权数据表");
        }
        return new Plan(
                sql,
                compiled,
                List.copyOf(state.boundModels),
                List.copyOf(state.relationships),
                state.relationships.isEmpty()
                        ? List.of()
                        : List.of("自动 JOIN 依赖模型声明的 to-one 基数，尚未验证维度键唯一性。"));
    }

    private static Select parseSelect(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 12000)
            throw invalid("SQL 不能为空且不得超过 12000 字符");
        if (CCJSqlParserUtil.getNestingDepth(sql) > 24) throw invalid("SQL 嵌套过深");
        try {
            var statement = CCJSqlParserUtil.parse(sql, p -> p.withTimeOut(2000));
            if (!(statement instanceof Select select)) throw invalid("仅支持单条只读 SELECT 查询");
            return select;
        } catch (net.sf.jsqlparser.JSQLParserException e) {
            throw invalid("SQL 解析失败，请检查语法或拆分复杂查询");
        }
    }

    private static Expression parseExpression(String sql) {
        if (sql == null
                || sql.isBlank()
                || sql.length() > 4000
                || CCJSqlParserUtil.getNestingDepth(sql) > 16) throw invalid("模型表达式为空或过于复杂");
        try {
            return CCJSqlParserUtil.parseExpression(sql, false, p -> p.withTimeOut(2000));
        } catch (net.sf.jsqlparser.JSQLParserException e) {
            throw invalid("无法解析模型表达式");
        }
    }

    private static final class Relation {
        final String model;
        final Set<String> columns;

        Relation(String model, Set<String> columns) {
            this.model = model;
            this.columns = columns;
        }
    }

    private static final class Scope {
        final Map<String, Relation> relations = new LinkedHashMap<>();
        final Scope parent;

        Scope(Scope parent) {
            this.parent = parent;
        }
    }

    private static final class State {
        final SemanticModel manifest;
        final Map<String, TableBinding> bindings;
        final Map<String, SemanticModelTable> models = new LinkedHashMap<>();
        final Map<String, Set<String>> used = new LinkedHashMap<>();
        final Map<String, String> cteNames = new LinkedHashMap<>();
        final Set<String> boundModels = new LinkedHashSet<>();
        final Set<String> physicalTables = new LinkedHashSet<>();
        final Set<String> relationships = new LinkedHashSet<>();

        State(SemanticModel manifest, Map<String, TableBinding> bindings) {
            this.manifest = manifest;
            this.bindings = bindings;
            if (manifest == null || manifest.getModels() == null) throw invalid("未配置语义模型");
            for (var table : manifest.getModels()) {
                String name = key(table.getName());
                if (name.startsWith("__sem_") || models.putIfAbsent(name, table) != null)
                    throw invalid("重复或保留的模型名称: " + table.getName());
            }
        }

        Set<String> select(
                Select select, Map<String, Set<String>> inherited, Scope parent, int depth) {
            if (depth > 24) throw invalid("查询嵌套过深");
            Map<String, Set<String>> ctes = new LinkedHashMap<>(inherited);
            if (select.getWithItemsList() != null) {
                for (WithItem item : select.getWithItemsList()) {
                    String name = key(item.getAlias().getName());
                    if (item.isRecursive()
                            || name.startsWith("__sem_")
                            || ctes.containsKey(name)
                            || item.getWithItemList() != null) throw invalid("不支持递归、重名或列重命名 CTE");
                    ctes.put(name, select(item.getSelect(), ctes, null, depth + 1));
                }
            }
            if (select.getForClause() != null
                    || select.getIsolation() != null
                    || select.getLimitBy() != null
                    || select.getFetch() != null
                    || select.isOracleSiblings()) throw invalid("不支持此 SELECT 附加子句");
            checkLimit(select);
            if (select instanceof ParenthesedSelect p) {
                if (p.getPivot() != null || p.getUnPivot() != null) throw invalid("不支持 PIVOT");
                Set<String> out = select(p.getSelect(), ctes, parent, depth + 1);
                checkOrder(select, new Scope(parent), ctes, out, depth);
                return out;
            }
            if (select instanceof SetOperationList set) {
                if (set.getOperations().stream().anyMatch(op -> !(op instanceof UnionOp)))
                    throw invalid("目前仅支持 UNION 集合运算");
                Set<String> out = null;
                for (Select child : set.getSelects()) {
                    Set<String> cols = select(child, ctes, parent, depth + 1);
                    if (out == null) out = cols;
                }
                checkOrder(select, new Scope(null), ctes, out == null ? Set.of() : out, depth);
                return out == null ? Set.of() : out;
            }
            if (!(select instanceof PlainSelect p)) throw invalid("不支持此查询类型");
            rejectExtras(p);
            Scope scope = new Scope(parent);
            from(p.getFromItem(), scope, ctes, depth);
            if (p.getJoins() != null) {
                for (Join join : p.getJoins()) {
                    if (join.isNatural()
                            || join.isApply()
                            || join.isSemi()
                            || join.isGlobal()
                            || join.isWindowJoin()
                            || join.isStraight()
                            || join.getJoinHint() != null
                            || join.isFull()
                            || (join.getUsingColumns() != null
                                    && !join.getUsingColumns().isEmpty()))
                        throw invalid("JOIN 请使用明确的 ON 条件，不支持 NATURAL/USING/特殊 JOIN");
                    from(join.getRightItem(), scope, ctes, depth);
                    if (join.getOnExpressions() != null) {
                        List<Expression> ons = new ArrayList<>();
                        for (Expression on : join.getOnExpressions())
                            ons.add(expr(on, c -> column(c, scope), scope, ctes, depth + 1));
                        join.setOnExpressions(ons);
                    }
                }
            }
            Set<String> outputs = new LinkedHashSet<>();
            List<SelectItem<?>> items = new ArrayList<>();
            for (SelectItem<?> item : p.getSelectItems()) {
                Expression e = item.getExpression();
                if (e instanceof AllColumns) {
                    List<Map.Entry<String, Relation>> targets =
                            new ArrayList<>(scope.relations.entrySet());
                    if (e instanceof AllTableColumns star) {
                        String alias = key(star.getTable().getName());
                        targets.removeIf(t -> !t.getKey().equals(alias));
                    }
                    if (targets.isEmpty()) throw invalid("无法展开星号字段");
                    for (var target : targets) {
                        for (String name : target.getValue().columns) {
                            if (target.getValue().model != null
                                    && field(target.getValue().model, name).getRelationship()
                                            != null) continue;
                            Column col = new Column(new Table(quote(target.getKey())), quote(name));
                            column(col, scope);
                            items.add(new SelectItem<>(col));
                            outputs.add(name);
                        }
                    }
                } else {
                    expr(e, c -> column(c, scope), scope, ctes, depth + 1);
                    String label =
                            item.getAlias() != null
                                    ? key(item.getAlias().getName())
                                    : e instanceof Column c
                                            ? key(c.getColumnName())
                                            : key(e.toString());
                    outputs.add(label);
                    items.add(item);
                }
            }
            p.setSelectItems(items);
            expr(p.getWhere(), c -> column(c, scope), scope, ctes, depth + 1);
            Function<Column, Expression> aliasResolver =
                    c ->
                            unqualified(c) && outputs.contains(key(c.getColumnName()))
                                    ? c
                                    : column(c, scope);
            if (p.getGroupBy() != null) {
                if (p.getGroupBy().getGroupingSets() != null
                        && !p.getGroupBy().getGroupingSets().isEmpty())
                    throw invalid("不支持 GROUPING SETS");
                expr(
                        p.getGroupBy().getGroupByExpressionList(),
                        aliasResolver,
                        scope,
                        ctes,
                        depth + 1);
            }
            expr(p.getHaving(), aliasResolver, scope, ctes, depth + 1);
            checkOrder(p, scope, ctes, outputs, depth);
            return outputs;
        }

        void rejectExtras(PlainSelect p) {
            PlainSelect allowed = new PlainSelect();
            allowed.setSelectItems(p.getSelectItems());
            allowed.setFromItem(p.getFromItem());
            allowed.setJoins(p.getJoins());
            allowed.setWhere(p.getWhere());
            allowed.setHaving(p.getHaving());
            allowed.setGroupByElement(p.getGroupBy());
            allowed.setDistinct(p.getDistinct());
            allowed.setOrderByElements(p.getOrderByElements());
            allowed.setLimit(p.getLimit());
            allowed.setOffset(p.getOffset());
            allowed.setWithItemsList(p.getWithItemsList());
            if (!allowed.toString().equals(p.toString())) throw invalid("SELECT 包含未支持的修饰符、写入或锁子句");
            if (p.getDistinct() != null && p.getDistinct().getOnSelectItems() != null)
                throw invalid("不支持 DISTINCT ON");
        }

        void checkLimit(Select s) {
            if (s.getLimit() != null) {
                numeric(s.getLimit().getRowCount());
                numeric(s.getLimit().getOffset());
                if (s.getLimit().isLimitAll() || s.getLimit().isLimitNull())
                    throw invalid("LIMIT 必须为非负整数");
            }
            if (s.getOffset() != null) numeric(s.getOffset().getOffset());
        }

        void numeric(Expression e) {
            if (e != null && (!(e instanceof LongValue value) || value.getValue() < 0))
                throw invalid("LIMIT/OFFSET 必须为非负整数");
        }

        void checkOrder(
                Select s,
                Scope scope,
                Map<String, Set<String>> ctes,
                Set<String> outputs,
                int depth) {
            if (s.getOrderByElements() != null)
                for (var order : s.getOrderByElements())
                    expr(
                            order.getExpression(),
                            c ->
                                    unqualified(c) && outputs.contains(key(c.getColumnName()))
                                            ? c
                                            : column(c, scope),
                            scope,
                            ctes,
                            depth + 1);
        }

        void from(FromItem item, Scope scope, Map<String, Set<String>> ctes, int depth) {
            if (item == null) return;
            if (item.getPivot() != null || item.getUnPivot() != null) throw invalid("不支持 PIVOT");
            String alias;
            Relation relation;
            if (item instanceof Table table) {
                if (table.getSchemaName() != null
                        || table.getDatabase() != null
                                && table.getDatabase().getDatabaseName() != null
                        || table.getNameParts().size() != 1
                        || table.getIndexHint() != null)
                    throw invalid("请仅使用语义模型名，不可指定物理 schema/数据库");
                String name = key(table.getName());
                alias = item.getAlias() == null ? name : key(item.getAlias().getName());
                if (ctes.containsKey(name)) relation = new Relation(null, ctes.get(name));
                else {
                    if (!models.containsKey(name)) throw invalid("未知或不可访问的逻辑模型: " + name);
                    used.computeIfAbsent(name, n -> new LinkedHashSet<>());
                    String cte = cteNames.computeIfAbsent(name, n -> "__sem_" + cteNames.size());
                    Set<String> columns = new LinkedHashSet<>();
                    for (SemanticColumn col : models.get(name).getColumns()) {
                        if (!columns.add(key(col.getName()))) throw invalid("模型字段重名: " + name);
                    }
                    relation = new Relation(name, columns);
                    table.setName(quote(cte));
                    if (table.getAlias() == null) table.setAlias(new Alias(quote(alias), false));
                }
            } else if (item instanceof ParenthesedSelect sub) {
                if (sub.getAlias() == null) throw invalid("子查询必须有别名");
                alias = key(sub.getAlias().getName());
                relation = new Relation(null, select(sub.getSelect(), ctes, null, depth + 1));
            } else throw invalid("不支持表函数、LATERAL 或此 FROM 结构");
            if (alias.startsWith("__sem_") || scope.relations.putIfAbsent(alias, relation) != null)
                throw invalid("表别名重复或使用保留前缀");
        }

        Expression column(Column c, Scope scope) {
            String name = key(c.getColumnName());
            String alias = unqualified(c) ? null : key(c.getTable().getName());
            if (!unqualified(c) && c.getTable().getNameParts().size() != 1)
                throw invalid("列引用不可指定数据库/schema");
            for (Scope current = scope; current != null; current = current.parent) {
                List<Relation> found = new ArrayList<>();
                for (var entry : current.relations.entrySet()) {
                    if ((alias == null || alias.equals(entry.getKey()))
                            && entry.getValue().columns.contains(name)) found.add(entry.getValue());
                }
                if (found.size() > 1) throw invalid("字段存在歧义，请使用表别名: " + name);
                if (found.size() == 1) {
                    Relation r = found.get(0);
                    if (r.model != null) {
                        if (field(r.model, name).getRelationship() != null)
                            throw invalid("关系 handle 不能直接查询: " + name);
                        used.get(r.model).add(name);
                    }
                    return c;
                }
                if (alias != null && current.relations.containsKey(alias)) break;
            }
            throw invalid("未知字段或别名: " + c);
        }

        Expression expr(
                Expression e,
                Function<Column, Expression> resolve,
                Scope scope,
                Map<String, Set<String>> ctes,
                int depth) {
            if (e == null) return null;
            if (depth > 40) throw invalid("表达式嵌套过深");
            Function<Expression, Expression> recurse =
                    x -> expr(x, resolve, scope, ctes, depth + 1);
            if (e instanceof Column c) return resolve.apply(c);
            if (e instanceof StringValue
                    || e instanceof LongValue
                    || e instanceof DoubleValue
                    || e instanceof NullValue
                    || e instanceof DateValue
                    || e instanceof TimeValue
                    || e instanceof TimestampValue
                    || e instanceof DateTimeLiteralExpression) return e;
            if (e instanceof BinaryExpression b && BINARY.contains(e.getClass().getSimpleName())) {
                b.setLeftExpression(recurse.apply(b.getLeftExpression()));
                b.setRightExpression(recurse.apply(b.getRightExpression()));
                if (b instanceof LikeExpression l && l.getEscape() != null)
                    recurse.apply(l.getEscape());
            } else if (e instanceof Parenthesis p)
                p.setExpression(recurse.apply(p.getExpression()));
            else if (e instanceof SignedExpression s)
                s.setExpression(recurse.apply(s.getExpression()));
            else if (e instanceof NotExpression n)
                n.setExpression(recurse.apply(n.getExpression()));
            else if (e instanceof IsNullExpression n)
                n.setLeftExpression(recurse.apply(n.getLeftExpression()));
            else if (e instanceof IsBooleanExpression b)
                b.setLeftExpression(recurse.apply(b.getLeftExpression()));
            else if (e instanceof Between b) {
                b.setLeftExpression(recurse.apply(b.getLeftExpression()));
                b.setBetweenExpressionStart(recurse.apply(b.getBetweenExpressionStart()));
                b.setBetweenExpressionEnd(recurse.apply(b.getBetweenExpressionEnd()));
            } else if (e instanceof InExpression in) {
                in.setLeftExpression(recurse.apply(in.getLeftExpression()));
                in.setRightExpression(recurse.apply(in.getRightExpression()));
            } else if (e instanceof ExistsExpression ex)
                ex.setRightExpression(recurse.apply(ex.getRightExpression()));
            else if (e instanceof ExpressionList<?> list) {
                @SuppressWarnings("unchecked")
                ExpressionList<Expression> values = (ExpressionList<Expression>) list;
                for (int i = 0; i < values.size(); i++) values.set(i, recurse.apply(values.get(i)));
            } else if (e instanceof net.sf.jsqlparser.expression.Function f) {
                if (!FUNCTIONS.contains(f.getName().toUpperCase(Locale.ROOT))
                        || f.getMultipartName().size() != 1
                        || f.getAttribute() != null
                        || f.getNamedParameters() != null
                        || f.getKeep() != null
                        || f.getOrderByElements() != null
                        || f.isEscaped()) throw invalid("不支持的函数: " + f.getName());
                if (f.getParameters() != null) {
                    @SuppressWarnings("unchecked")
                    ExpressionList<Expression> args =
                            (ExpressionList<Expression>) f.getParameters();
                    for (int i = 0; i < args.size(); i++) {
                        if (args.get(i) instanceof AllColumns
                                && f.getName().equalsIgnoreCase("COUNT")) continue;
                        args.set(i, recurse.apply(args.get(i)));
                    }
                }
            } else if (e instanceof CaseExpression c) {
                c.setSwitchExpression(recurse.apply(c.getSwitchExpression()));
                c.setElseExpression(recurse.apply(c.getElseExpression()));
                for (WhenClause w : c.getWhenClauses()) {
                    w.setWhenExpression(recurse.apply(w.getWhenExpression()));
                    w.setThenExpression(recurse.apply(w.getThenExpression()));
                }
            } else if (e instanceof CastExpression c) {
                if (!c.getColDataType()
                        .toString()
                        .toUpperCase(Locale.ROOT)
                        .matches(
                                "(CHAR|VARCHAR|DATE|DATETIME|TIMESTAMP|DECIMAL|INTEGER|INT|BIGINT|SIGNED|UNSIGNED|DOUBLE)(\\([0-9,"
                                    + " ]+\\))?")) throw invalid("不支持的 CAST 类型");
                c.setLeftExpression(recurse.apply(c.getLeftExpression()));
            } else if (e instanceof ParenthesedSelect sub && scope != null)
                select(sub.getSelect(), ctes, scope, depth + 1);
            else throw invalid("不支持的表达式: " + e.getClass().getSimpleName());
            return e;
        }

        SemanticColumn field(String model, String name) {
            return models.get(model).getColumns().stream()
                    .filter(c -> key(c.getName()).equals(name))
                    .findFirst()
                    .orElseThrow(() -> invalid("模型字段不存在: " + model + "." + name));
        }

        TableBinding binding(String model) {
            SemanticModelTable table = models.get(model);
            TableBinding binding = bindings.get(table.getName());
            if (binding == null) throw invalid("模型未绑定到当前知识库的数据表: " + table.getName());
            if (table.getRefSql() != null || table.getBaseObject() != null)
                throw invalid("暂不支持 refSql/baseObject 模型");
            if (binding.schema() == null || binding.schema().isBlank())
                throw invalid("数据表缺少已授权 schema");
            boundModels.add(table.getName());
            physicalTables.add(key(quote(binding.schema()) + "." + quote(binding.tableName())));
            return binding;
        }

        String physical(String model) {
            TableBinding b = binding(model);
            return quote(b.schema()) + "." + quote(b.tableName());
        }

        Column physicalColumn(String model, String column, String alias) {
            TableBinding b = binding(model);
            String actual =
                    b.columns().stream()
                            .filter(n -> key(n).equals(column))
                            .findFirst()
                            .orElseThrow(() -> invalid("物理字段未登记: " + model + "." + column));
            return new Column(new Table(quote(alias)), quote(actual));
        }

        String modelSql(String model) {
            Map<String, String> joins = new LinkedHashMap<>();
            List<String> fields = new ArrayList<>();
            for (String name : used.get(model)) {
                fields.add(
                        expand(model, name, joins, new LinkedHashSet<>())
                                + " AS "
                                + quote(field(model, name).getName()));
            }
            if (fields.isEmpty()) fields.add("1 AS `__row`");
            return "SELECT "
                    + String.join(", ", fields)
                    + " FROM "
                    + physical(model)
                    + " AS `__base` "
                    + String.join(" ", joins.values());
        }

        Expression expand(
                String model, String name, Map<String, String> joins, Set<String> visiting) {
            SemanticColumn col = field(model, name);
            if (col.getRelationship() != null) throw invalid("关系 handle 不能直接查询");
            if (!col.isCalculated()) return physicalColumn(model, name, "__base");
            if (!visiting.add(name)) throw invalid("计算列循环依赖: " + name);
            Expression value =
                    expr(
                            parseExpression(col.getExpression()),
                            c -> {
                                if (unqualified(c) || key(c.getTable().getName()).equals(model))
                                    return expand(model, key(c.getColumnName()), joins, visiting);
                                if (c.getTable().getNameParts().size() != 1)
                                    throw invalid("不支持多跳计算路径");
                                String handleName = key(c.getTable().getName());
                                SemanticColumn handle = field(model, handleName);
                                if (handle.getRelationship() == null)
                                    throw invalid("计算列引用未知关系 handle");
                                SemanticRelationship rel =
                                        manifest.findRelationship(handle.getRelationship());
                                if (rel == null || rel.getModels().size() != 2)
                                    throw invalid("关系定义无效");
                                int side =
                                        key(rel.getModels().get(0)).equals(model)
                                                ? 0
                                                : key(rel.getModels().get(1)).equals(model)
                                                        ? 1
                                                        : -1;
                                if (side < 0
                                        || !("ONE_TO_ONE".equalsIgnoreCase(rel.getJoinType())
                                                || side == 0
                                                        && "MANY_TO_ONE"
                                                                .equalsIgnoreCase(rel.getJoinType())
                                                || side == 1
                                                        && "ONE_TO_MANY"
                                                                .equalsIgnoreCase(
                                                                        rel.getJoinType())))
                                    throw invalid("不支持自动 to-many JOIN");
                                String target = key(rel.getModels().get(1 - side));
                                if (target.equals(model) || !models.containsKey(target))
                                    throw invalid("不支持此关系目标");
                                String alias = "__rel_" + handleName;
                                if (!joins.containsKey(handleName)) {
                                    Expression on =
                                            expr(
                                                    parseExpression(rel.getCondition()),
                                                    joinCol -> {
                                                        if (unqualified(joinCol))
                                                            throw invalid("关系 ON 字段必须带模型名");
                                                        String qualifier =
                                                                key(joinCol.getTable().getName());
                                                        if (!qualifier.equals(model)
                                                                && !qualifier.equals(target))
                                                            throw invalid("关系条件引用无关模型");
                                                        return physicalColumn(
                                                                qualifier,
                                                                key(joinCol.getColumnName()),
                                                                qualifier.equals(model)
                                                                        ? "__base"
                                                                        : alias);
                                                    },
                                                    null,
                                                    Map.of(),
                                                    0);
                                    joins.put(
                                            handleName,
                                            "LEFT JOIN "
                                                    + physical(target)
                                                    + " AS "
                                                    + quote(alias)
                                                    + " ON "
                                                    + on);
                                    relationships.add(rel.getName());
                                }
                                SemanticColumn targetCol = field(target, key(c.getColumnName()));
                                if (targetCol.isCalculated() || targetCol.getRelationship() != null)
                                    throw invalid("不支持多跳计算字段");
                                return physicalColumn(target, key(c.getColumnName()), alias);
                            },
                            null,
                            Map.of(),
                            0);
            visiting.remove(name);
            return new Parenthesis(value);
        }
    }

    private static boolean unqualified(Column c) {
        return c.getTable() == null || c.getTable().getName() == null;
    }

    private static String key(String s) {
        return s == null ? "" : s.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static String quote(String s) {
        if (s == null || s.isBlank() || s.length() > 128 || !s.matches("[\\p{L}\\p{N}_$]+"))
            throw invalid("不支持的标识符");
        return "`" + s + "`";
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
