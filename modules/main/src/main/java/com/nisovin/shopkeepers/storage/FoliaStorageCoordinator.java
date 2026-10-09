package com.nisovin.shopkeepers.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Coordinates immutable owner snapshots, tombstones, and disk acknowledgements.
 */
final class FoliaStorageCoordinator {

	static final class Ticket {
		final int id;
		final Object owner;
		final long version;
		private long snapshotRevision;
		private @Nullable String snapshot;

		Ticket(int id, Object owner, long version) {
			this.id = id;
			this.owner = owner;
			this.version = version;
		}
	}

	static final class Write {
		private final String header;
		private final List<String> records;
		final long revision;

		Write(String header, List<String> records, long revision) {
			this.header = header;
			this.records = records;
			this.revision = revision;
		}

		String data() {
			StringBuilder data = new StringBuilder(header);
			records.forEach(data::append);
			return data.toString();
		}
	}

	private final Map<String, String> records = new LinkedHashMap<>();
	private final Map<Integer, Ticket> dirty = new HashMap<>();
	private final Map<Integer, Ticket> latest = new HashMap<>();
	private final Map<Integer, Ticket> ready = new HashMap<>();
	private final Map<Integer, Long> unsaved = new HashMap<>();
	private final Map<Integer, Long> deleted = new HashMap<>();
	private final Set<Integer> usedIds = new HashSet<>();
	private final Set<Integer> reservedIds = new HashSet<>();
	private long revision;
	private long savedRevision;
	private int nextId = 1;
	private boolean closed;

	synchronized void reset() {
		records.clear();
		dirty.clear();
		latest.clear();
		ready.clear();
		unsaved.clear();
		deleted.clear();
		usedIds.clear();
		reservedIds.clear();
		savedRevision = ++revision;
		nextId = 1;
		closed = false;
	}

	synchronized void seed(String key, String data) {
		records.put(key, data);
		try {
			int id = Integer.parseInt(key);
			if (id > 0) this.useId(id);
		} catch (NumberFormatException e) {
			// Preserve non-shopkeeper entries as well.
		}
	}

	synchronized int reserveId() {
		if (closed) throw new IllegalStateException("Storage snapshot collection has stopped!");
		int firstId = nextId > 0 ? nextId : 1;
		int id = firstId;
		while (usedIds.contains(id) || reservedIds.contains(id)) {
			id = id == Integer.MAX_VALUE ? 1 : id + 1;
			if (id == firstId) throw new IllegalStateException("No unused shopkeeper ids available!");
		}

		reservedIds.add(id);
		nextId = id + 1;
		return id;
	}

	synchronized void releaseId(int id) {
		if (reservedIds.remove(id) && id == nextId - 1) nextId = id;
	}

	synchronized void useId(int id) {
		reservedIds.remove(id);
		usedIds.add(id);
		if (nextId > 0 && id >= nextId) nextId = id + 1;
	}

	synchronized @Nullable Ticket markDirty(int id, Object owner, long version) {
		if (closed || deleted.containsKey(id)) return null;
		Ticket previous = latest.get(id);
		if (previous != null && previous.owner == owner) {
			if (previous.version > version) return null;
			if (previous.version == version) return dirty.get(id) == previous ? previous : null;
		}

		this.useId(id);
		++revision;
		Ticket ticket = new Ticket(id, owner, version);
		dirty.put(id, ticket);
		latest.put(id, ticket);
		return ticket;
	}

	synchronized boolean isCurrent(Ticket ticket) {
		return !closed && dirty.get(ticket.id) == ticket;
	}

	synchronized boolean publish(Ticket ticket, String data) {
		if (!this.isCurrent(ticket) || ticket.snapshot != null) return false;
		ticket.snapshot = data;
		ticket.snapshotRevision = ++revision;
		ready.put(ticket.id, ticket);
		return true;
	}

	synchronized Ticket[] pendingSnapshots() {
		return dirty.values().stream().filter(ticket -> ticket.snapshot == null)
				.toArray(Ticket[]::new);
	}

	synchronized void delete(int id) {
		if (closed) throw new IllegalStateException("Storage snapshot collection has stopped!");
		dirty.remove(id);
		latest.remove(id);
		ready.remove(id);
		records.remove(String.valueOf(id));
		unsaved.remove(id);
		deleted.put(id, ++revision);
	}

	synchronized void requestSave() {
		if (!closed) ++revision;
	}

	synchronized boolean isDirty() {
		return revision > savedRevision || !dirty.isEmpty() || !unsaved.isEmpty()
				|| !deleted.isEmpty();
	}

	synchronized int dirtyCount() {
		Set<Integer> ids = new HashSet<>(unsaved.keySet());
		ids.addAll(dirty.keySet());
		return ids.size();
	}

	synchronized int deletedCount() {
		return deleted.size();
	}

	synchronized Write prepare(String header) {
		ready.values().forEach(ticket -> {
			String snapshot = ticket.snapshot;
			assert snapshot != null;
			records.put(String.valueOf(ticket.id), snapshot);
			unsaved.put(ticket.id, ticket.snapshotRevision);
			dirty.remove(ticket.id, ticket);
		});
		ready.clear();
		return new Write(header, List.copyOf(records.values()), revision);
	}

	synchronized void acknowledge(Write write) {
		savedRevision = Math.max(savedRevision, write.revision);
		unsaved.values().removeIf(value -> value <= write.revision);
		deleted.entrySet().removeIf(entry -> {
			if (entry.getValue() > write.revision) return false;
			usedIds.remove(entry.getKey());
			return true;
		});
	}

	synchronized void close() {
		// Ready snapshots remain available for the lifecycle flush.
		closed = true;
	}
}
