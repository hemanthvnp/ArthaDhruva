import type { BorrowerContact } from '../api/types';
import { initials } from './borrower';

/** Who a borrower can reach about their loan. Messaging is a mailto link for now (in-app messaging is out of
 * scope); without a contact on file the card still says where to turn, so it is never missing. */
export default function ContactCard({ contact, compact = false }: { contact: BorrowerContact | null; compact?: boolean }) {
  return (
    <div className={`officer-row${compact ? ' compact' : ''}`}>
      <span className="avatar" aria-hidden="true">{contact ? initials(contact.name) : '?'}</span>
      <div className="officer-info">
        {contact ? (
          <>
            <div className="name">{contact.name}</div>
            <div className="role">{contact.title ?? 'Your lender'}</div>
          </>
        ) : (
          <>
            <div className="name">Your lender</div>
            <div className="role">Use the phone number or address on your statement.</div>
          </>
        )}
      </div>
      {contact?.email && (
        <a className="button-link secondary" href={`mailto:${contact.email}`}>
          Message
        </a>
      )}
    </div>
  );
}
