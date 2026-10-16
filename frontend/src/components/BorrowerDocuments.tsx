import { useRef, useState, type DragEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { downloadMyDocument, myDocuments, uploadMyDocument } from '../api/client';
import type { BorrowerDocument } from '../api/types';
import ErrorBanner from './ErrorBanner';
import Icon from './Icon';
import { fileSize } from './borrower';
import { dateOnly } from '../format';

/** A borrower's documents on one of their own loans: list, view (download) and add. The backend checks the
 * loan is theirs on every call; this component never sees another loan's id. */
export default function BorrowerDocuments({ loanId }: { loanId: string }) {
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const documents = useQuery({ queryKey: ['my-documents', loanId], queryFn: () => myDocuments(loanId) });
  const upload = useMutation({
    mutationFn: (file: File) => uploadMyDocument(loanId, file),
    onSuccess: (added) =>
      queryClient.setQueryData<BorrowerDocument[]>(['my-documents', loanId], (previous) => [added, ...(previous ?? [])]),
  });
  const view = useMutation({ mutationFn: (d: BorrowerDocument) => downloadMyDocument(loanId, d.id, d.filename) });

  const onFiles = (files: FileList | null) => {
    const file = files?.[0];
    if (file) upload.mutate(file);
    if (input.current) input.current.value = '';
  };
  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragging(false);
    onFiles(e.dataTransfer.files);
  };

  const list = documents.data ?? [];
  return (
    <div>
      <ErrorBanner error={documents.error ?? upload.error ?? view.error} />
      {documents.isLoading && <p className="page-subtitle">Loading...</p>}
      {!documents.isLoading && list.length === 0 && !documents.error && (
        <p className="page-subtitle">No documents on your loan yet.</p>
      )}
      {list.length > 0 && (
        <ul className="doc-list">
          {list.map((d) => (
            <li className="list-row" key={d.id}>
              <span className="icon-circle" aria-hidden="true"><Icon name="file" size={16} /></span>
              <div className="grow">
                <div className="doc-name">{d.filename}</div>
                <div className="sub">
                  Added {dateOnly(d.uploadedAt)} by {d.uploadedByYou ? 'you' : 'your lender'} &middot; {fileSize(d.sizeBytes)}
                </div>
              </div>
              <button type="button" className="secondary small" onClick={() => view.mutate(d)} disabled={view.isPending}>
                View
              </button>
            </li>
          ))}
        </ul>
      )}
      <div
        className={`dropzone${dragging ? ' active' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <Icon name="upload" size={16} />
        <span>{upload.isPending ? 'Uploading...' : 'Drop a file here, or'}</span>
        <button type="button" className="secondary small" onClick={() => input.current?.click()} disabled={upload.isPending}>
          browse
        </button>
        <input
          ref={input}
          type="file"
          hidden
          aria-label="Choose a document to upload"
          accept=".pdf,.png,.jpg,.jpeg,.docx,.xlsx,.txt,.csv"
          onChange={(e) => onFiles(e.target.files)}
        />
      </div>
      <p className="hint">PDF, PNG, JPEG, Word, Excel, plain text or CSV. Files are checked before they are stored.</p>
    </div>
  );
}
