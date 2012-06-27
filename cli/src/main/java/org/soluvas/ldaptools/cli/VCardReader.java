package org.soluvas.ldaptools.cli;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import net.sourceforge.cardme.engine.VCardEngine;
import net.sourceforge.cardme.vcard.VCard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ActorSystem;
import akka.dispatch.Future;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.japi.Function;

import com.google.common.collect.Lists;

/**
 * Read vCard from File.
 * @author ceefour
 */
public class VCardReader {
	
	private transient Logger log = LoggerFactory.getLogger(VCardReader.class);
	private VCardEngine vCardEngine = new VCardEngine();
	@Inject private ActorSystem actorSystem;
	
	public Future<VCard> read(final File inputVcard) {
		return Futures.future(new Callable<VCard>() {
			@Override
			public VCard call() throws Exception {
				log.debug("Reading vCard file {}", inputVcard);
				return vCardEngine.parse(inputVcard);
			}
		}, actorSystem.dispatcher());
	}

	public Future<List<VCard>> read(final Iterable<File> inputVcards) {
		Future<Iterable<VCard>> iterableFuture = Futures.traverse(inputVcards, new Function<File, Future<VCard>>() {
			@Override
			public Future<VCard> apply(final File inputVcard) {
				return read(inputVcard);
			}
		}, actorSystem.dispatcher());
		return iterableFuture.map(new Mapper<Iterable<VCard>, List<VCard>>() {
			@Override
			public List<VCard> apply(Iterable<VCard> vcardIterable) {
				return Lists.newArrayList(vcardIterable);
			}
		});
	}
	
}
