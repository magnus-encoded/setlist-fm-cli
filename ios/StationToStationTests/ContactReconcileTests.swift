import XCTest
@testable import StationToStation

/// The LAN reconcile decision between two **Contacts** (#265), the Swift twin of
/// Android's `ContactReconcileTest`. No radio, no socket, no Keychain, no device — which
/// is the whole reason this logic was pulled out into a pure function to begin with.
///
/// Names are invented: this repository is public and real timeline data never enters a
/// fixture.
final class ContactReconcileTests: XCTestCase {

    private func photo(_ id: String, ref: String = "asset/mine/") -> StoredMedia {
        StoredMedia(id: id, kind: StoredMedia.Kind.photo, ref: ref + id)
    }

    private func offered(_ id: String, hash: String) -> OfferedMedia {
        OfferedMedia(id: id, gigId: "a", kind: StoredMedia.Kind.photo, hash: hash, from: "their-key")
    }

    private func cache(gigs: [String: StoredGig] = [:],
                       gigMedia: [String: [StoredMedia]] = [:]) -> TimelineCache {
        var c = TimelineCache()
        c.gigs = gigs
        c.gigMedia = gigMedia
        return c
    }

    private func manifest(timeline: TimelineCache = TimelineCache(),
                          media: [OfferedMedia]) -> HandoverManifest {
        HandoverManifest(timeline: timeline, media: media)
    }

    // MARK: - The plan

    /// The fail-safe posture, and the reason `verified` is an argument rather than
    /// something computed in here: a peer that has not proven who they are gets nothing,
    /// by construction rather than by an upstream caller remembering to check.
    func testAnUnverifiedPeerYieldsAnEmptyPlan() {
        let offer = manifest(media: [offered("m1", hash: "h1")])

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: false)

        XCTAssertTrue(plan.held.isEmpty)
        XCTAssertTrue(plan.fromGallery.isEmpty)
        XCTAssertTrue(plan.request.isEmpty)
    }

    func testAnItemAlreadyHeldByIdIsNeitherRequestedNorPulledFromTheGallery() {
        let mine = cache(gigMedia: ["a": [photo("m1")]])
        let offer = manifest(media: [offered("m1", hash: "h1")])

        let plan = contactReconcilePlan(mine: mine, offer: offer, verified: true)

        XCTAssertEqual(["m1"], plan.held)
        XCTAssertTrue(plan.fromGallery.isEmpty)
        XCTAssertTrue(plan.request.isEmpty)
    }

    func testAHashMatchInTheGalleryResolvesWithoutARequest() {
        let offer = manifest(media: [offered("m1", hash: "h1")])
        let gallery = [GalleryItem(ref: "asset/gallery/x", hash: "h1")]

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer,
                                        verified: true, gallery: gallery)

        XCTAssertEqual(["m1": "asset/gallery/x"], plan.fromGallery)
        XCTAssertTrue(plan.request.isEmpty)
    }

    func testUnmatchedMediaIsRequested() {
        let offer = manifest(media: [offered("m1", hash: "h1")])

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true)

        XCTAssertEqual(["m1"], plan.request)
    }

    /// A video hashes to nothing on this platform (see `PhotoLibrary.mediaHash`), so an
    /// empty hash must never be treated as a match — every empty-hashed item would
    /// otherwise resolve to whichever unhashable thing the gallery listed first.
    func testAnEmptyHashNeverMatchesTheGallery() {
        let offer = manifest(media: [offered("m1", hash: "")])
        let gallery = [GalleryItem(ref: "asset/gallery/x", hash: "")]

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer,
                                        verified: true, gallery: gallery)

        XCTAssertTrue(plan.fromGallery.isEmpty)
        XCTAssertEqual(["m1"], plan.request)
    }

    /// What lets an Exchange visit re-diff on every discovery instead of tracking any
    /// session state of its own: walking in and out of range twice is the same plan
    /// twice, not two half-plans.
    func testRunningThePlanTwiceAgainstTheSameManifestsIsIdempotent() {
        let mine = cache(gigMedia: ["a": [photo("m1")]])
        let offer = manifest(media: [offered("m1", hash: "h1"),
                                     offered("m2", hash: "h2"),
                                     offered("m3", hash: "h3")])
        let gallery = [GalleryItem(ref: "asset/gallery/x", hash: "h2")]

        let first = contactReconcilePlan(mine: mine, offer: offer, verified: true, gallery: gallery)
        let second = contactReconcilePlan(mine: mine, offer: offer, verified: true, gallery: gallery)

        XCTAssertEqual(first, second)
        XCTAssertEqual(["m1"], first.held)
        XCTAssertEqual(["m2": "asset/gallery/x"], first.fromGallery)
        XCTAssertEqual(["m3"], first.request)
    }

    // MARK: - The landing

    func testAResolvedItemLandsOnTheGigSharingItsSetlistIdNotItsSenderSideGigId() {
        let mine = cache(gigs: ["mine-gig": StoredGig(id: "mine-gig", setlistId: "sl-1")])
        let offer = manifest(
            timeline: cache(gigs: ["their-gig": StoredGig(id: "their-gig", setlistId: "sl-1")],
                            gigMedia: ["their-gig": [photo("m1")]]),
            media: [offered("m1", hash: "h1")]
        )

        let landing = contactLanding(mine: mine, offer: offer,
                                     resolved: ["m1": "asset/gallery/x"])

        XCTAssertEqual(1, landing.count)
        guard let item = landing["mine-gig"]?.first else { return XCTFail("nothing landed") }
        XCTAssertEqual("m1", item.id)
        XCTAssertEqual("asset/gallery/x", item.ref)
        // Attribution survives the transfer, or which photographs were whose is
        // unrecoverable the moment they are mingled into someone's nights.
        XCTAssertEqual("their-key", item.from)
    }

    /// This never mints a night. A Contact's offer is a shared band, not a device's own
    /// history, so a night I have no record of attending is not one for their photographs
    /// to create.
    func testANightIHaveNoRecordOfAttendingLandsNothing() {
        let offer = manifest(
            timeline: cache(gigs: ["their-gig": StoredGig(id: "their-gig", setlistId: "sl-1")],
                            gigMedia: ["their-gig": [photo("m1")]]),
            media: [offered("m1", hash: "h1")]
        )

        let landing = contactLanding(mine: TimelineCache(), offer: offer,
                                     resolved: ["m1": "asset/gallery/x"])

        XCTAssertTrue(landing.isEmpty)
    }

    /// A record whose reference points at nothing is the dead reference #97 exists to
    /// prevent, so an item joins only once its bytes have actually arrived.
    func testAnItemWithNoResolvedRefYetDoesNotLand() {
        let mine = cache(gigs: ["mine-gig": StoredGig(id: "mine-gig", setlistId: "sl-1")])
        let offer = manifest(
            timeline: cache(gigs: ["their-gig": StoredGig(id: "their-gig", setlistId: "sl-1")],
                            gigMedia: ["their-gig": [photo("m1")]]),
            media: [offered("m1", hash: "h1")]
        )

        let landing = contactLanding(mine: mine, offer: offer, resolved: [:])

        XCTAssertTrue(landing.isEmpty)
    }

    // MARK: - What a Contact is offered in the first place

    /// The whole privacy boundary of this feature, asserted where it is decided: an item
    /// in the vault is absent from the manifest, not filtered out of it downstream.
    func testAPersonalItemNeverEntersAContactManifest() {
        var vault = photo("m2")
        vault.personal = true
        let mine = cache(gigMedia: ["a": [photo("m1"), vault]])

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertEqual(["m1"], offered.media.map(\.id))
        XCTAssertEqual(["m1"], offered.timeline.gigMedia["a"]?.map(\.id))
    }

    /// Passing a Contact's photograph on to my other Contacts would be publishing on
    /// their behalf — a second path for their picture that they never agreed to.
    func testReceivedMediaIsNeverOfferedOnward() {
        var theirs = photo("m2")
        theirs.from = "someone-elses-key"
        let mine = cache(gigMedia: ["a": [photo("m1"), theirs]])

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertEqual(["m1"], offered.media.map(\.id))
    }

    func testEveryOfferedItemIsAttributedToMeAndMarkedShared() {
        let mine = cache(gigMedia: ["a": [photo("m1")]])

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertEqual(["my-key"], offered.media.map { $0.from ?? "" })
        XCTAssertEqual([false], offered.media.map(\.personal))
        // In the source's own gig ids: translating them is the receiver's job.
        XCTAssertEqual(["a"], offered.media.map(\.gigId))
    }

    /// A night that shares nothing drops out of the manifest rather than travelling as an
    /// empty entry — the far end has no use for the fact that it exists.
    func testANightSharingNothingIsNotOffered() {
        var vault = photo("m1")
        vault.personal = true
        let mine = cache(gigMedia: ["a": [vault], "b": [photo("m2")]])

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertEqual(["b"], Array(offered.timeline.gigMedia.keys))
        XCTAssertEqual(["m2"], offered.media.map(\.id))
    }
}
