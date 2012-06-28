package org.soluvas.ldaptools.cli;

import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import javax.inject.Inject;
import javax.inject.Named;

import net.sourceforge.cardme.vcard.VCard;
import net.sourceforge.cardme.vcard.features.AddressFeature;
import net.sourceforge.cardme.vcard.features.EmailFeature;
import net.sourceforge.cardme.vcard.features.ExtendedFeature;
import net.sourceforge.cardme.vcard.features.URLFeature;

import org.apache.directory.shared.ldap.model.entry.DefaultEntry;
import org.apache.directory.shared.ldap.model.entry.Entry;
import org.apache.directory.shared.util.Strings;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.soluvas.slug.SlugUtils;

import akka.actor.ActorSystem;
import akka.dispatch.Future;
import akka.dispatch.Futures;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Convert {@link VCard} objects to LDAP {@link Entry}.
 * @author ceefour
 */
public class VCard2EntryConverter {
	
	private transient Logger log = LoggerFactory.getLogger(VCard2EntryConverter.class);
	@Inject private ActorSystem actorSystem;
	@Inject @Named("ldapUsersDn") private String usersDn;
//	@Inject private SchemaManager schemaManager;
	
	public VCard2EntryConverter() {
		super();
	}
	
	public VCard2EntryConverter(ActorSystem actorSystem/*, SchemaManager schemaManager*/) {
		super();
		this.actorSystem = actorSystem;
//		this.schemaManager = schemaManager;
	}
	
	public Future<Entry> asEntry(final VCard vCard) {
		return Futures.future(new Callable<Entry>() {
			@Override
			public Entry call() throws Exception {
				log.debug("Converting vCard to LDAP Entry for {}", vCard.getFormattedName().getFormattedName());
				
				// Prepare name inputs
				String formattedName = null;
				if (vCard.getFormattedName() != null && Strings.isNotEmpty(vCard.getFormattedName().getFormattedName()))
					formattedName = vCard.getFormattedName().getFormattedName();
				String givenName = null;
				if (vCard.getName() != null && Strings.isNotEmpty(vCard.getName().getGivenName()))
					givenName = vCard.getName().getGivenName();
				String familyName = null;
				if (vCard.getName() != null && Strings.isNotEmpty(vCard.getName().getFamilyName()))
					familyName = vCard.getName().getFamilyName();
				
				// Complete non-existing names
				String personId = vCard.hasUID() ? vCard.getUID().getUID() : SlugUtils.generateId(formattedName);
				String dn = "uid=" + personId + "," + usersDn;
				
//				DefaultEntry entry = new DefaultEntry(schemaManager);
				DefaultEntry entry = new DefaultEntry(dn);
				entry.add("objectClass", "organizationalPerson", "uidObject", "extensibleObject", "socialPerson", "facebookObject",
						"simpleSecurityObject");
				entry.add("cn", formattedName);
				entry.add("givenName", givenName);
				entry.add("sn", familyName);
				
				for (EmailFeature email : Lists.newArrayList(vCard.getEmails()))
					entry.add("mail", email.getEmail());
				
//				NICKNAME:shansaiichan
				if (vCard.hasNicknames()) {
					for (Iterator<String> iter = vCard.getNicknames().getNicknames(); iter.hasNext(); ) {
						String nickname = iter.next();
						entry.add("nickname", nickname);
					}
//					if (Strings.isNotEmpty(nickname))
//						entry.add("uniqueIdentifier", nickname);
				}
				
//				ADR;TYPE=INTL,POSTAL,PARCEL,WORK,PREF:;;;Surabaya;;;Indonesia
//				ADR;TYPE=INTL,POSTAL,PARCEL,HOME:;;;Surabaya;;;Indonesia
				if (vCard.hasAddresses()) {
					AddressFeature address = vCard.getAddresses().next();
					if (address.hasStreetAddress() && Strings.isNotEmpty(address.getStreetAddress()))
						entry.add("street", address.getStreetAddress());
					if (address.hasLocality() && Strings.isNotEmpty(address.getLocality()))
						entry.add("l", address.getLocality());
					if (address.hasRegion() && Strings.isNotEmpty(address.getRegion()))
						entry.add("st", address.getRegion());
					if (address.hasCountryName() && Strings.isNotEmpty(address.getCountryName()))
						entry.add("c", address.getCountryName());
				}
				
//				URL:http://www.facebook.com/shansaiichan
				if (vCard.hasURLs()) {
					for (Iterator<URLFeature> iter = vCard.getURLs(); iter.hasNext(); ) {
						URLFeature feature = iter.next();
						entry.add("websiteUri", feature.getURL().toString() );
					}
				}

//				BDAY:1993-09-21T00:00:00Z
				if (vCard.hasBirthday()) {
					DateTime birthDate = new DateTime(vCard.getBirthDay().getBirthday());
					String birthDateStr = birthDate.withZone(DateTimeZone.UTC).toString("yyyyMMddHHmm'Z'");
					entry.add("birthDate", birthDateStr);
				}

				entry.add("virtualMail", personId + "@member.berbatik.com");
				
				// TODO: configurable default password, or if it is omitted
				entry.add("userPassword", "bippo");

				List<ExtendedFeature> extendedTypes = ImmutableList.copyOf(vCard.getExtendedTypes());
				for (ExtendedFeature ext : extendedTypes) {
					if ("X-SCREENNAME".equals(ext.getExtensionName())) {
						entry.add("uniqueIdentifier", ext.getExtensionData());
					}
//					X-FACEBOOK-ID:1225303239
					if ("X-FACEBOOK-ID".equals(ext.getExtensionName())) {
						entry.add("facebookId", ext.getExtensionData());
					}
//					X-FACEBOOK-USERNAME:shansaiichan
					if ("X-FACEBOOK-USERNAME".equals(ext.getExtensionName())) {
						entry.add("facebookUsername", ext.getExtensionData());
					}
					// X-GENDER:Female
					if ("X-GENDER".equals(ext.getExtensionName())) {
						entry.add("gender", ext.getExtensionData().toLowerCase());
					}
				}
				
//				PHOTO;ENCODING=BASE64:/9j/4AAQSkZJRgABAgAAAQABAAD//gAEKgD/4gIcSUNDX1BST0ZJT
				
				return entry;
			}
		}, actorSystem.dispatcher());
	}

}
