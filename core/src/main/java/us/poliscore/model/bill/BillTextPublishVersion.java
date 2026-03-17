package us.poliscore.model.bill;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

public enum BillTextPublishVersion {
	/// Important! ///
	// The ordering of the enums here is used to determine which bill text to select //
	///////
	
	// Introduced
	IH,
	IS,

	// Amended
	AS,
	ASH,
	EAH,
	EAS,

	// Bill in deliberation
	ATH,
	ATS,
	CPH,
	CPS,
	EH,
	EPH,
	ES,
	HDH,
	HDS,
	OPH,
	OPS,
	PAV,
	PCH,
	PCS,
	PP,
	PWAH,
	RAH,
	RAS,
	RCH,
	RCS,
	RDH,
	RDS,
	REAH,
	RES,
	RENR,
	RFH,
	RFS,
	RH,
	RHUC,
	RIH,
	RIS,
	RS,
	RTH,
	RTS,
	SAS,
	SC,

	// Headed to President
	ENR,

	// Finalized (success)
	PAP,

	// Finalized (thrown out)
	CDH,
	CDS,
	FAH,
	FPH,
	FPS,
	IPH,
	IPS,
	LTH,
	LTS;
	
	public static BillTextPublishVersion parseFromBillTextName(String fileName)
	{
		var bn = FilenameUtils.getBaseName(fileName);
		String normalized = normalizeFileNameSuffix(bn);
		
		for (BillTextPublishVersion v : BillTextPublishVersion.values())
		{
			if (normalized.endsWith(v.name()))
			{
				return v;
			}
		}
		
		throw new RuntimeException("file name " + fileName + " could not be parsed");
	}
	
	protected static String normalizeFileNameSuffix(String fileBaseName) {
		String normalized = StringUtils.upperCase(fileBaseName);
		
		// GPO sometimes appends stage-count digits (e.g. RH2, RFS2)
		// and/or star-print markers (e.g. EH1S = first-star-print of EH).
		normalized = normalized.replaceFirst("\\d+S$", "");
		normalized = normalized.replaceFirst("\\d+$", "");
		
		return normalized;
	}

	/**
	 * Can be used to sort bill publish versions based on the maturity of a bill
	 * 
	 * @param fromBillTextName
	 * @return
	 */
	public int billMaturityCompareTo(BillTextPublishVersion fromBillTextName) {
		return this.compareTo(fromBillTextName);
	}
}
