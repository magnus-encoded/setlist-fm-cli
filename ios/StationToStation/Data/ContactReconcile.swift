import Foundation

/// The other pairwise sync (#257/#265): two **Contacts**, same WiFi, whatever's sitting
/// in the shared band that the far end is still missing. Ported term-for-term from
/// Android's `data/ContactReconcile.kt` — the same reason `ContactView` is a port and
/// not a re-derivation. Two implementations that can disagree eventually will, and here
/// the direction of the disagreement is *sending someone more than they were offered*.
///
/// Not a device handover: that is a union of one person's whole timeline across their
/// own devices. This is the opposite trust model, over a manifest `contactManifest` has
/// already narrowed to exactly what a Contact may see. That narrowing is why this is so
/// small — there is no category allow-list to apply and no night to decide is off-limits,
/// because `offer` arrives pre-filtered. What's left is only "do I already have this".
struct ContactReconcilePlan: Equatable {
    /// Media ids I already hold under the same id.
    var held: [String] = []
    /// Media id → the reference to my own copy, matched by hash. No bytes cross the
    /// wire for these.
    var fromGallery: [String: String] = [:]
    /// Media ids to ask for.
    var request: [String] = []
}

/// The LAN reconcile decision. Pure: no radio, no socket, no clock — the same split
/// Android makes, for the same reason. This is the hardest-to-get-right part of #265 and
/// the one part that needs neither a device nor a networking stack to check.
///
/// `verified` is the challenge-response outcome (a signature over a nonce, checked
/// against the Contact's persisted `Friend.publicKey`), reached by the caller and passed
/// in rather than computed here. False yields an empty plan — a peer that hasn't proven
/// who they are gets nothing, and gets it by construction rather than by remembering to
/// check upstream.
///
/// Idempotent: running it twice against the same `mine`/`offer` yields the same plan,
/// which is what lets an Exchange visit simply re-diff on every discovery rather than
/// track session state of its own.
func contactReconcilePlan(
    mine: TimelineCache,
    offer: HandoverManifest,
    verified: Bool,
    gallery: [GalleryItem] = []
) -> ContactReconcilePlan {
    if !verified { return ContactReconcilePlan() }

    let mineIds = Set(mine.gigMedia.values.flatMap { $0 }.map(\.id))
    var byHash: [String: String] = [:]
    for item in gallery where !item.hash.isEmpty && byHash[item.hash] == nil {
        byHash[item.hash] = item.ref
    }

    var plan = ContactReconcilePlan()
    for item in offer.media {
        if mineIds.contains(item.id) {
            plan.held.append(item.id)
        } else if let ref = byHash[item.hash] {
            plan.fromGallery[item.id] = ref
        } else {
            plan.request.append(item.id)
        }
    }
    return plan
}

/// The dumb half of `contactReconcilePlan`: turns resolved items into what
/// `TimelineStore.mergeContactMedia` should write. `resolved` is media id → my own local
/// ref — the plan's `fromGallery` entries as soon as the plan exists, its `request`
/// entries once their bytes have actually arrived over the wire.
///
/// A received item only lands on a night I already have, matched by `setlistId` — the one
/// key that means the same thing on two people's timelines (#28). This never mints a new
/// **Gig**: a Contact's offer is narrowed to a shared band, not a device's own history,
/// so a night I have no record of attending is not one for their photographs to create.
func contactLanding(
    mine: TimelineCache,
    offer: HandoverManifest,
    resolved: [String: String]
) -> [String: [StoredMedia]] {
    var setlistToGigId: [String: String] = [:]
    for gig in mine.gigs.values {
        if let setlistId = gig.setlistId?.nilIfBlank { setlistToGigId[setlistId] = gig.id }
    }
    var attribution: [String: String] = [:]
    for item in offer.media { attribution[item.id] = item.from }

    var landing: [String: [StoredMedia]] = [:]
    for (theirGigId, items) in offer.timeline.gigMedia {
        guard let setlistId = offer.timeline.gigs[theirGigId]?.setlistId,
              let myGigId = setlistToGigId[setlistId]
        else { continue }
        let landed: [StoredMedia] = items.compactMap { item in
            guard let ref = resolved[item.id] else { return nil }
            var copy = item
            copy.ref = ref
            copy.from = attribution[item.id] ?? item.from
            return copy
        }
        if !landed.isEmpty { landing[myGigId] = landed }
    }
    return landing
}
