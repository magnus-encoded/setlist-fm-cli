import Foundation

/// One LAN reconcile visit, start to finish, over an already-connected `wire` (#265):
/// `mutualContactAuth`, manifest exchange, `contactReconcilePlan`, then streaming whatever
/// each side actually asked for. Ported from Android's `exchange/ContactSession.kt`.
///
/// Everything after auth is symmetric except *order* — the server side always moves first
/// at every step, the same fixed-order trick `mutualContactAuth` uses, so neither end ever
/// blocks both directions on a read at once.
///
/// Returns the landing map ready for `TimelineStore.mergeContactMedia`, or nil the moment
/// the peer fails to verify as a known Contact. **Nothing is exchanged with a stranger,
/// not even a manifest** — the verify happens before a single field of my timeline is
/// described, which is what keeps "who are your Contacts" off the network too.
func runContactSession(
    wire: ContactConnection,
    isServer: Bool,
    peerCertificate: Data,
    candidates: [String],
    myManifest: HandoverManifest,
    mine: TimelineCache,
    gallery: [GalleryItem],
    /// A readable local file holding the full-resolution bytes for a media id, or nil if
    /// I no longer have it. A file rather than a stream because a `PHAsset`'s bytes are
    /// not a path — the caller exports first, and owns cleaning up after.
    mediaSource: (String) async -> URL?,
    /// Where a received item's bytes land, named for its id and its offered kind.
    receivedFile: (String, String) -> URL
) async throws -> [String: [StoredMedia]]? {
    guard try await mutualContactAuth(wire, isServer: isServer,
                                      peerCertificate: peerCertificate,
                                      candidates: candidates) != nil
    else { return nil }

    guard let theirManifest = try await exchangeManifests(wire, isServer: isServer, mine: myManifest)
    else { return nil }

    let plan = contactReconcilePlan(mine: mine, offer: theirManifest, verified: true, gallery: gallery)
    let theirRequest = try await exchangeRequests(wire, isServer: isServer, mine: plan.request)

    var kinds: [String: String] = [:]
    for item in theirManifest.media { kinds[item.id] = item.kind }

    var resolved = plan.fromGallery
    if isServer {
        try await sendRequested(wire, ids: theirRequest, mediaSource: mediaSource)
        resolved.merge(try await receiveRequested(wire, receivedFile: receivedFile, kinds: kinds)) { _, new in new }
    } else {
        resolved.merge(try await receiveRequested(wire, receivedFile: receivedFile, kinds: kinds)) { _, new in new }
        try await sendRequested(wire, ids: theirRequest, mediaSource: mediaSource)
    }

    return contactLanding(mine: mine, offer: theirManifest, resolved: resolved)
}

private func exchangeManifests(_ wire: ContactConnection, isServer: Bool,
                               mine: HandoverManifest) async throws -> HandoverManifest? {
    if isServer {
        try await writeJson(wire, mine)
        return try await readJson(wire, HandoverManifest.self)
    }
    let theirs = try await readJson(wire, HandoverManifest.self)
    try await writeJson(wire, mine)
    return theirs
}

private func exchangeRequests(_ wire: ContactConnection, isServer: Bool,
                              mine: [String]) async throws -> [String] {
    if isServer {
        try await writeJson(wire, mine)
        return try await readJson(wire, [String].self) ?? []
    }
    let theirs = try await readJson(wire, [String].self) ?? []
    try await writeJson(wire, mine)
    return theirs
}

/// An item I no longer hold is skipped rather than refused: the far end asked for it
/// because my manifest offered it, and a gallery can empty between the two moments.
private func sendRequested(_ wire: ContactConnection, ids: [String],
                           mediaSource: (String) async -> URL?) async throws {
    for id in ids {
        guard let url = await mediaSource(id),
              let handle = try? FileHandle(forReadingFrom: url),
              let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber
        else { continue }
        defer { try? handle.close() }
        let length = size.intValue
        try await writeJson(wire, ItemHeader(id: id, bytes: Int64(length)))
        try await wire.writeBody(length: length) { try handle.read(upToCount: 64 * 1024) }
    }
    try await writeEndOfItems(wire)
}

/// Media id → the local ref its received bytes now live at.
///
/// Always drains up to the end-of-items marker even when nothing was asked for: the
/// marker is unconditional on the sending side, so skipping this read would leave it
/// sitting unread on a connection meant to carry more frames afterwards.
///
/// The bytes are written under a temporary name and moved into place only once every
/// declared byte has arrived, which is what makes a dropped transfer leave a coherent
/// subset rather than a half-written file wearing a real name.
private func receiveRequested(_ wire: ContactConnection,
                              receivedFile: (String, String) -> URL,
                              kinds: [String: String]) async throws -> [String: String] {
    var landed: [String: String] = [:]
    while let header = try await readItemHeader(wire) {
        let destination = receivedFile(header.id, kinds[header.id] ?? StoredMedia.Kind.photo)
        let partial = destination.appendingPathExtension("part")
        try? FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        FileManager.default.createFile(atPath: partial.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: partial) else {
            // The bytes are still coming whether or not there is anywhere to put them,
            // so they have to be drained rather than abandoned mid-frame.
            try await wire.readBody(length: Int(header.bytes)) { _ in }
            continue
        }
        do {
            try await wire.readBody(length: Int(header.bytes)) { try handle.write(contentsOf: $0) }
            try? handle.close()
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: partial, to: destination)
            landed[header.id] = destination.path
        } catch {
            try? handle.close()
            try? FileManager.default.removeItem(at: partial)
            throw error
        }
    }
    return landed
}
