package us.poliscore.openstates;

import java.time.LocalDate;

import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.CsvDate;

import lombok.Data;

@Data
public class OpenStatesLegislatorData {
	@CsvBindByName(column = "id")
    private String id;

    @CsvBindByName(column = "name")
    private String name;

    @CsvBindByName(column = "current_party")
    private String currentParty;

    @CsvBindByName(column = "current_district")
    private String currentDistrict;

    @CsvBindByName(column = "current_chamber")
    private String currentChamber;

    @CsvBindByName(column = "given_name")
    private String givenName;

    @CsvBindByName(column = "family_name")
    private String familyName;

    @CsvBindByName(column = "gender")
    private String gender;

    @CsvBindByName(column = "email")
    private String email;

    @CsvBindByName(column = "biography")
    private String biography;

    @CsvBindByName(column = "birth_date")
    @CsvDate("yyyy-MM-dd")
    private LocalDate birthDate;

    @CsvBindByName(column = "death_date")
    @CsvDate("yyyy-MM-dd")
    private LocalDate deathDate;

    @CsvBindByName(column = "image")
    private String image;

    @CsvBindByName(column = "links")
    private String links;

    @CsvBindByName(column = "sources")
    private String sources;

    @CsvBindByName(column = "capitol_address")
    private String capitolAddress;

    @CsvBindByName(column = "capitol_voice")
    private String capitolVoice;

    @CsvBindByName(column = "capitol_fax")
    private String capitolFax;

    @CsvBindByName(column = "district_address")
    private String districtAddress;

    @CsvBindByName(column = "district_voice")
    private String districtVoice;

    @CsvBindByName(column = "district_fax")
    private String districtFax;

    @CsvBindByName(column = "twitter")
    private String twitter;

    @CsvBindByName(column = "youtube")
    private String youtube;

    @CsvBindByName(column = "instagram")
    private String instagram;

    @CsvBindByName(column = "facebook")
    private String facebook;

    @CsvBindByName(column = "wikidata")
    private String wikidata;
}
