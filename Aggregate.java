package simpledb;

import jdk.nashorn.api.scripting.ScriptObjectMirror;

import java.util.*;

import static simpledb.Aggregator.NO_GROUPING;

/**
 * The Aggregation operator that computes an aggregate (e.g., sum, avg, max,
 * min). Note that we only support aggregates over a single column, grouped by a
 * single column.
 */
public class Aggregate extends Operator {

    private static final long serialVersionUID = 1L;

    OpIterator[] childs;
    int afield, gfield;
    Aggregator.Op aop;
    Aggregator aggregator;

    /**
     * Constructor.
     * 
     * Implementation hint: depending on the type of afield, you will want to
     * construct an {@link IntegerAggregator} or {@link StringAggregator} to help
     * you with your implementation of readNext().
     * 
     * 
     * @param child
     *            The OpIterator that is feeding us tuples.
     * @param afield
     *            The column over which we are computing an aggregate.
     * @param gfield
     *            The column over which we are grouping the result, or -1 if
     *            there is no grouping
     * @param aop
     *            The aggregation operator to use
     */
    public Aggregate(OpIterator child, int afield, int gfield, Aggregator.Op aop) {
        this.childs = new OpIterator[]{ child };
        this.afield = afield;
        this.gfield = gfield;
        this.aop = aop;

        TupleDesc td = child.getTupleDesc();
        Type[] types = td.getTypes();
        Type gbType = gfield == NO_GROUPING ? null : types[gfield];
        if (types[afield] == Type.INT_TYPE)
            aggregator = new IntegerAggregator(gfield, gbType, afield, aop);
        else
            aggregator = new StringAggregator(gfield, gbType, afield, aop);
    }

    /**
     * @return If this aggregate is accompanied by a groupby, return the groupby
     *         field index in the <b>INPUT</b> tuples. If not, return
     *         {@link simpledb.Aggregator#NO_GROUPING}
     * */
    public int groupField() {
        return gfield;
    }

    /**
     * @return If this aggregate is accompanied by a group by, return the name
     *         of the groupby field in the <b>OUTPUT</b> tuples. If not, return
     *         null;
     * */
    public String groupFieldName() {
	    if (gfield != NO_GROUPING)
	        return childs[0].getTupleDesc().getFieldName(0);
	    return null;
    }

    /**
     * @return the aggregate field
     * */
    public int aggregateField() {
        return afield;
    }

    /**
     * @return return the name of the aggregate field in the <b>OUTPUT</b>
     *         tuples
     * */
    public String aggregateFieldName() {
	// some code goes here
	    return childs[0].getTupleDesc().getFieldName(1);
    }

    /**
     * @return return the aggregate operator
     * */
    public Aggregator.Op aggregateOp() {
        return aop;
    }

    public static String nameOfAggregatorOp(Aggregator.Op aop) {
	    return aop.toString();
    }

    public void open() throws NoSuchElementException, DbException,
	    TransactionAbortedException {
        super.open();
        OpIterator iterator = childs[0];
        iterator.open();
        while (iterator.hasNext()) {
            Tuple tuple = iterator.next();
            aggregator.mergeTupleIntoGroup(tuple);
        }
        aggregator.iterator().open();
        //aggregator.iterator().open();
    }

    /**
     * Returns the next tuple. If there is a group by field, then the first
     * field is the field by which we are grouping, and the second field is the
     * result of computing the aggregate. If there is no group by field, then
     * the result tuple should contain one field representing the result of the
     * aggregate. Should return null if there are no more tuples.
     */
    protected Tuple fetchNext() throws TransactionAbortedException, DbException {
	    OpIterator iterator = aggregator.iterator();
	    if (iterator.hasNext())
	        return iterator.next();
	    return null;
    }

    public void rewind() throws DbException, TransactionAbortedException {
        aggregator.iterator().rewind();
    }

    /**
     * Returns the TupleDesc of this Aggregate. If there is no group by field,
     * this will have one field - the aggregate column. If there is a group by
     * field, the first field will be the group by field, and the second will be
     * the aggregate value column.
     * 
     * The name of an aggregate column should be informative. For example:
     * "aggName(aop) (child_td.getFieldName(afield))" where aop and afield are
     * given in the constructor, and child_td is the TupleDesc of the child
     * iterator.
     */
    public TupleDesc getTupleDesc() {
	    return aggregator.iterator().getTupleDesc();
    }

    public void close() {
        super.close();
        aggregator.iterator().close();
    }

    @Override
    public OpIterator[] getChildren() {
	    return childs;
    }

    @Override
    public void setChildren(OpIterator[] children) {
	    childs = children;
    }
    
}
