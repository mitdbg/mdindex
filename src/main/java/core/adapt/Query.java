package core.adapt;

import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;

import core.common.globals.TableInfo;
import org.apache.hadoop.io.Text;

import com.google.common.base.Joiner;

import core.adapt.iterator.IteratorRecord;
import core.common.globals.Globals;
import core.common.key.RawIndexKey;

public class Query implements Serializable {
	private static final long serialVersionUID = 1L;

	protected Predicate[] predicates;

	private String table;

    RawIndexKey key;

	public Query(String queryString) {
        System.out.println("QUERY: " + queryString);
		String[] parts = queryString.split("\\|");
        System.out.println("Parts: " + parts.length);
		this.table = parts[0];
		String predString = parts[1].trim();
		if (predString.length() > 0) {
			String[] predParts = predString.split(";");
            System.out.println("PredParts" + predParts.length);
            this.predicates = new Predicate[predParts.length];
            for (int i = 0; i < predParts.length; i++) {
                this.predicates[i] = new Predicate(predParts[i]);
            }
		} else {
			this.predicates = new Predicate[0];
		}
	}
	
	public Query(String table, Predicate[] predicates) {
		this.table = table;
		this.predicates = predicates;
	}

	public Predicate[] getPredicates() {
		return this.predicates;
	}

    public String getTable() { return this.table; }

	public void write(DataOutput out) throws IOException {
		Text.writeString(out, toString());
	}

    /**
     * Load information about table if not already done.
     */
    public void loadKey() {
        if (key == null) {
            TableInfo tableInfo = Globals.getTableInfo(table);
            if (tableInfo == null) {
                // TODO: throw exception ?
                throw new RuntimeException("Table Info for table " + table + " not loaded");
            }

            key = new RawIndexKey(tableInfo.delimiter);
        }
    }

	public boolean qualifies(IteratorRecord record) {
        loadKey();

		boolean qualify = true;
		for (Predicate p : predicates) {
			int attrIdx = p.attribute;
			switch (p.type) {
			case BOOLEAN:
				qualify &= p.isRelevant(record.getBooleanAttribute(attrIdx));
				break;
			case INT:
				qualify &= p.isRelevant(record.getIntAttribute(attrIdx));
				break;
			case LONG:
				qualify &= p.isRelevant(record.getLongAttribute(attrIdx));
				break;
			case DOUBLE:
				qualify &= p.isRelevant(record.getFloatAttribute(attrIdx));
				break;
			case DATE:
				qualify &= p.isRelevant(record.getDateAttribute(attrIdx));
				break;
			case STRING:
				qualify &= p.isRelevant(record.getStringAttribute(attrIdx,
						20));
				break;
			case VARCHAR:
				qualify &= p.isRelevant(record.getStringAttribute(attrIdx,
						100));
				break;
			default:
				throw new RuntimeException("Invalid data type!");
			}
		}
		return qualify;
	}

	@Override
	public String toString() {
		return table + "|" + Joiner.on(";").join(predicates);
	}
}