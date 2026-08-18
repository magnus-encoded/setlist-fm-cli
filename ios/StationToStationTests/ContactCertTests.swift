import CryptoKit
import Security
import XCTest
@testable import StationToStation

/// The one check that makes hand-written ASN.1 defensible (#265).
///
/// iOS has no API for minting a self-signed certificate and `Network.framework` will not
/// start a TLS server without one, so `ContactCert` encodes the DER itself. That is only
/// acceptable because the platform will tell us when it is wrong:
/// `SecCertificateCreateWithData` refuses malformed DER outright, and the public key it
/// parses back out has to be the one that went in. Both are asserted here rather than
/// discovered on a phone.
final class ContactCertTests: XCTestCase {

    func testTheMintedCertificateParsesAndCarriesTheKeyItWasMadeFor() throws {
        guard let tls = ContactTlsIdentity.make() else {
            throw XCTSkip("no keychain available in this environment")
        }
        defer { tls.discard() }

        // Refuses anything that is not well-formed DER, which is the assertion.
        let parsed = try XCTUnwrap(SecCertificateCreateWithData(nil, tls.certificate as CFData),
                                   "the encoded certificate is not valid DER")

        let publicKey = try XCTUnwrap(SecCertificateCopyKey(parsed))
        let x963 = try XCTUnwrap(SecKeyCopyExternalRepresentation(publicKey, nil) as Data?)
        XCTAssertNoThrow(try P256.Signing.PublicKey(x963Representation: x963),
                         "the certificate's key is not the P-256 key it was signed with")

        var fromIdentity: SecCertificate?
        XCTAssertEqual(errSecSuccess, SecIdentityCopyCertificate(tls.identity, &fromIdentity))
        // The identity has to pair *this* certificate with its key, or the TLS handshake
        // presents somebody else's session cert and the fingerprint binding is nonsense.
        XCTAssertEqual(tls.certificate,
                       SecCertificateCopyData(try XCTUnwrap(fromIdentity)) as Data)
    }

    /// Every session gets its own — nothing here is meant to be recognised across
    /// sessions, and trust comes from the challenge rather than from this key.
    func testEachSessionMintsADistinctCertificate() throws {
        guard let first = ContactTlsIdentity.make(), let second = ContactTlsIdentity.make() else {
            throw XCTSkip("no keychain available in this environment")
        }
        defer { first.discard(); second.discard() }

        XCTAssertNotEqual(first.certificate, second.certificate)
        XCTAssertNotEqual(certFingerprint(first.certificate), certFingerprint(second.certificate))
    }

    /// The session is over and the keychain does not clean up after itself.
    func testDiscardingRemovesTheIdentityFromTheKeychain() throws {
        guard let tls = ContactTlsIdentity.make() else {
            throw XCTSkip("no keychain available in this environment")
        }
        let der = tls.certificate
        tls.discard()

        var items: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass as String: kSecClassIdentity,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnRef as String: true,
        ] as CFDictionary, &items)
        let identities = (status == errSecSuccess ? items as? [SecIdentity] : []) ?? []

        XCTAssertFalse(identities.contains { identity in
            var certificate: SecCertificate?
            guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess,
                  let certificate else { return false }
            return SecCertificateCopyData(certificate) as Data == der
        })
    }

    func testTheFingerprintIsSha256OfTheCertificateBytes() {
        let der = Data("not really a certificate".utf8)

        XCTAssertEqual(Data(SHA256.hash(data: der)), certFingerprint(der))
        XCTAssertEqual(32, certFingerprint(der).count)
    }
}
