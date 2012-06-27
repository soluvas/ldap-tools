package org.soluvas.ldaptools.cli;

import java.util.List;
import java.util.concurrent.Callable;

import javax.inject.Inject;
import javax.inject.Named;

import net.sourceforge.cardme.vcard.VCard;
import net.sourceforge.cardme.vcard.features.AddressFeature;
import net.sourceforge.cardme.vcard.features.EmailFeature;
import net.sourceforge.cardme.vcard.features.ExtendedFeature;
import net.sourceforge.cardme.vcard.types.ExtendedType;

import org.apache.directory.shared.ldap.model.entry.DefaultEntry;
import org.apache.directory.shared.ldap.model.entry.Entry;
import org.apache.directory.shared.util.Strings;
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
				if (formattedName == null)
					formattedName = (givenName + " " + familyName).trim();
				
				String personId = SlugUtils.generateId(formattedName, 0);
				String dn = "uid=" + personId + "," + usersDn;
				
//				DefaultEntry entry = new DefaultEntry(schemaManager);
				DefaultEntry entry = new DefaultEntry(dn);
				entry.add("objectClass", "organizationalPerson", "uidObject", "extensibleObject", "socialPerson", "facebookObject");
				entry.add("cn", formattedName);
				entry.add("gn", givenName);
				entry.add("sn", familyName);
				
				for (EmailFeature email : Lists.newArrayList(vCard.getEmails()))
					entry.add("mail", email.getEmail());
				
				if (vCard.hasNicknames()) {
					String nickname = vCard.getNicknames().getNicknames().next();
					if (Strings.isNotEmpty(nickname))
						entry.add("uniqueIdentifier", nickname);
				}
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

				entry.add("virtualMail", personId + "@member.berbatik.com");

				if (vCard.containsExtendedType(new ExtendedType("X-FACEBOOK-ID", null)) || vCard.containsExtendedType(new ExtendedType("X-FACEBOOK-USERNAME", null))) {
					List<ExtendedFeature> extendedTypes = ImmutableList.copyOf(vCard.getExtendedTypes());
					for (ExtendedFeature ext : extendedTypes) {
						if ("X-FACEBOOK-ID".equals(ext.getExtensionName())) {
							entry.add("facebookId", ext.getExtensionData());
						}
						if ("X-FACEBOOK-USERNAME".equals(ext.getExtensionName())) {
							entry.add("facebookUsername", ext.getExtensionData());
						}
						// TODO: X-GENDER:Female
						// TODO: BDAY
					}
				}
				
//				NICKNAME:shansaiichan
//				URL:http://www.facebook.com/shansaiichan
//				BDAY:1993-09-21T00:00:00Z
//				ADR;TYPE=INTL,POSTAL,PARCEL,WORK,PREF:;;;Surabaya;;;Indonesia
//				ADR;TYPE=INTL,POSTAL,PARCEL,HOME:;;;Surabaya;;;Indonesia
//				PHOTO;ENCODING=BASE64:/9j/4AAQSkZJRgABAgAAAQABAAD//gAEKgD/4gIcSUNDX1BST0ZJT
//				X-FACEBOOK-ID:1225303239
//				X-FACEBOOK-USERNAME:shansaiichan
//				X-GENDER:Female

				return entry;
			}
		}, actorSystem.dispatcher());
	}

}
