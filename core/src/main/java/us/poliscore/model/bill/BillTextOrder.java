package us.poliscore.model.bill;

import java.time.LocalDateTime;
import java.util.Comparator;

import org.apache.commons.lang3.StringUtils;

/** Shared ordering for selecting and serializing bill-text versions. */
public final class BillTextOrder {

	public static final Comparator<BillText> ASCENDING = BillTextOrder::compare;

	private static int compare(BillText left, BillText right) {
		if (left == right) return 0;
		if (left == null) return -1;
		if (right == null) return 1;

		if (isCongress(left) && isCongress(right)) {
			int leftMaturity = congressMaturity(left);
			int rightMaturity = congressMaturity(right);
			int maturity = Integer.compare(leftMaturity, rightMaturity);
			if (maturity != 0) return maturity;

			int canonical = Boolean.compare(
					BillTextIdentity.isCanonicalCongressVersion(left.getVersion()),
					BillTextIdentity.isCanonicalCongressVersion(right.getVersion()));
			if (canonical != 0) return canonical;
		}

		int publicationDate = Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder())
				.compare(left.getLastUpdate(), right.getLastUpdate());
		if (publicationDate != 0) return publicationDate;

		int version = StringUtils.compareIgnoreCase(left.getVersion(), right.getVersion(), true);
		if (version != 0) return version;
		return StringUtils.compare(left.getId(), right.getId(), true);
	}

	private static int congressMaturity(BillText text) {
		String canonical = BillTextIdentity
				.canonicalCongressVersionFromStoredVersion(text.getVersion(), text.getBillId())
				.orElse(null);
		if (canonical == null) return -1;

		try {
			BillTextPublishVersion version = BillTextPublishVersion.parseFromBillTextName("bill" + canonical);
			return version.ordinal();
		}
		catch (RuntimeException ignored) {
			return -1;
		}
	}

	private static boolean isCongress(BillText text) {
		return StringUtils.containsIgnoreCase(text.getBillId(), "/us/congress/");
	}

	private BillTextOrder() { }
}
