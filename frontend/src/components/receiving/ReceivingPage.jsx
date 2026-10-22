import { useEffect, useState } from 'react';
import { api } from '../../services/api';
import { date } from '../../utils/formatters';
import { Badge, Button, Card, EmptyState, ErrorState, Modal, Skeleton } from '../common/Ui';
import { PageHeader } from '../layout/Layout';
import { InvoiceReview } from './InvoiceReview';
import { CartonScanner } from './CartonScanner';
import { ConfirmPanel } from './ConfirmPanel';

const statusTone = { DRAFT: 'neutral', PROCESSING: 'warning', REVIEW_REQUIRED: 'warning', READY_TO_CONFIRM: 'success', CONFIRMED: 'success', CANCELLED: 'danger' };
const statusLabel = { DRAFT: 'Draft', PROCESSING: 'Extracting', REVIEW_REQUIRED: 'Review needed', READY_TO_CONFIRM: 'Ready to confirm', CONFIRMED: 'Confirmed', CANCELLED: 'Cancelled' };
const FILTERS = ['', 'DRAFT', 'REVIEW_REQUIRED', 'READY_TO_CONFIRM', 'CONFIRMED', 'CANCELLED'];

export function ReceivingPage({ toast }) {
  const [view, setView] = useState({ name: 'list' });
  const open = (receiptId) => setView({ name: 'detail', id: receiptId });
  return view.name === 'list'
    ? <ReceivingList toast={toast} open={open} onNew={() => setView({ name: 'create' })} />
    : view.name === 'create'
      ? <NewReceiptForm toast={toast} onCreated={open} onBack={() => setView({ name: 'list' })} />
      : <ReceiptDetail id={view.id} toast={toast} onBack={() => setView({ name: 'list' })} />;
}

function ReceivingList({ open, onNew }) {
  const [state, setState] = useState({ loading: true, data: null, error: '' });
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);

  const load = (statusValue, pageValue) => {
    setState({ loading: true, data: null, error: '' });
    api.receiving(pageValue, statusValue)
      .then((data) => setState({ loading: false, data, error: '' }))
      .catch((e) => setState({ loading: false, data: null, error: e.message }));
  };
  useEffect(() => { load(status, page); }, [status, page]);

  return <>
    <PageHeader eyebrow="SUPPLY CHAIN" title="Goods Receiving" text="Receive supplier deliveries with QR carton scanning, invoice extraction and audited stock commits."
      action={<Button onClick={onNew}>New receipt</Button>} />
    <div className="table-toolbar">
      <select aria-label="Filter by status" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
        {FILTERS.map((f) => <option key={f || 'all'} value={f}>{f ? statusLabel[f] : 'All statuses'}</option>)}
      </select>
    </div>
    {state.loading ? <Skeleton count={5} className="row-skeleton" />
      : state.error ? <ErrorState error={state.error} retry={() => load(status, page)} />
        : !state.data?.content?.length ? <EmptyState title="No receipts yet" text="Create a receipt to start receiving a supplier delivery." />
          : <Card className="table-card"><div className="table-wrap"><table>
            <thead><tr><th>Receipt</th><th>Supplier</th><th>Invoice</th><th>Created</th><th>Status</th><th>Cartons</th><th>Discrepancies</th><th/></tr></thead>
            <tbody>{state.data.content.map((r) => <tr key={r.id}>
              <td><b>#{r.id}</b></td>
              <td>{r.supplierName || '—'}</td>
              <td>{r.invoiceNumber || '—'}<small>{r.invoiceDate ? date(r.invoiceDate) : ''}</small></td>
              <td>{date(r.createdAt)}</td>
              <td><Badge tone={statusTone[r.status] || 'neutral'}>{statusLabel[r.status] || r.status}</Badge></td>
              <td>{r.scannedCartonCount}/{r.cartonCount}</td>
              <td>{r.unresolvedDiscrepancies > 0 ? <Badge tone="danger">{r.unresolvedDiscrepancies} unresolved</Badge> : <Badge tone="success">None</Badge>}</td>
              <td><Button variant="secondary" size="small" onClick={() => open(r.id)}>Open</Button></td>
            </tr>)}</tbody>
          </table></div>
            <div className="modal-actions">
              <Button variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
              <span>Page {page + 1}</span>
              <Button variant="ghost" disabled={page + 1 >= (state.data.totalPages || 1)} onClick={() => setPage(page + 1)}>Next</Button>
            </div>
          </Card>}
  </>;
}

function NewReceiptForm({ onCreated, onBack }) {
  const [form, setForm] = useState({ supplierName: '', invoiceNumber: '', invoiceDate: '', purchaseOrderRef: '', notes: '' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const set = (e) => setForm({ ...form, [e.target.name]: e.target.value });
  const submit = async (e) => {
    e.preventDefault();
    setSaving(true); setError('');
    try {
      const created = await api.createReceipt({ ...form, invoiceDate: form.invoiceDate || null });
      onCreated(created.id);
    } catch (err) { setError(err.message); } finally { setSaving(false); }
  };
  return <>
    <PageHeader eyebrow="GOODS RECEIVING" title="New delivery receipt" text="Enter the supplier delivery details. Inventory only changes when the receipt is confirmed."
      action={<Button variant="ghost" onClick={onBack}>Back to receipts</Button>} />
    <Card><form className="modal-form product-form" onSubmit={submit}>
      <label>Supplier name<input name="supplierName" value={form.supplierName} onChange={set} placeholder="e.g. Amul Dairy Distributors" /></label>
      <label>Invoice number<input name="invoiceNumber" value={form.invoiceNumber} onChange={set} placeholder="e.g. INV-2026-0142" /></label>
      <label>Invoice date<input type="date" name="invoiceDate" value={form.invoiceDate} onChange={set} /></label>
      <label>Purchase order / reference<input name="purchaseOrderRef" value={form.purchaseOrderRef} onChange={set} /></label>
      <label className="wide">Notes<textarea name="notes" rows="2" value={form.notes} onChange={set} /></label>
      {error && <p className="muted wide" role="alert">{error}</p>}
      <div className="modal-actions wide"><Button variant="ghost" type="button" onClick={onBack}>Cancel</Button><Button loading={saving}>Create draft receipt</Button></div>
    </form></Card>
  </>;
}

function ReceiptDetail({ id, toast, onBack }) {
  const [state, setState] = useState({ loading: true, data: null, error: '' });
  const [tab, setTab] = useState('invoice');
  const reload = () => {
    setState({ loading: true, data: null, error: '' });
    api.receiptDetail(id).then((data) => setState({ loading: false, data, error: '' })).catch((e) => setState({ loading: false, data: null, error: e.message }));
  };
  useEffect(reload, [id]);

  if (state.loading) return <><PageHeader eyebrow="GOODS RECEIVING" title={`Receipt #${id}`} /><Skeleton count={6} className="row-skeleton" /></>;
  if (state.error) return <><PageHeader eyebrow="GOODS RECEIVING" title={`Receipt #${id}`} /><ErrorState error={state.error} retry={reload} /></>;

  const { receipt, document, reconciliation, cartons } = state.data;
  const editable = ['DRAFT', 'PROCESSING', 'REVIEW_REQUIRED', 'READY_TO_CONFIRM'].includes(receipt.status);
  const tabs = [['invoice', 'Invoice review'], ['cartons', `Carton scanning (${cartons.filter((c) => c.status === 'SCANNED' || c.status === 'VERIFIED_SHORT').length})`], ['reconcile', 'Reconcile & confirm']];
  const cancel = async () => {
    try { await api.cancelReceipt(id); toast('Receipt cancelled'); reload(); } catch (e) { toast(e.message, 'error'); }
  };

  return <>
    <PageHeader eyebrow="GOODS RECEIVING" title={`Receipt #${receipt.id}${receipt.supplierName ? ` · ${receipt.supplierName}` : ''}`}
      text={`Invoice ${receipt.invoiceNumber || '—'}${receipt.invoiceDate ? ` dated ${date(receipt.invoiceDate)}` : ''} · ${statusLabel[receipt.status] || receipt.status}`}
      action={<div style={{ display: 'flex', gap: '0.5rem' }}>
        <Button variant="ghost" onClick={onBack}>Back</Button>
        {editable && <Button variant="ghost" onClick={cancel}>Cancel receipt</Button>}
      </div>} />
    <div className="table-toolbar" role="tablist">
      {tabs.map(([key, label]) => <button key={key} role="tab" aria-selected={tab === key} className={tab === key ? 'nav-active' : ''} onClick={() => setTab(key)}>{label}</button>)}
    </div>
    {tab === 'invoice' && <InvoiceReview id={id} receipt={receipt} document={document} reconciliation={reconciliation} editable={editable} reload={reload} toast={toast} />}
    {tab === 'cartons' && <CartonScanner id={id} receipt={receipt} cartons={cartons} reconciliation={reconciliation} editable={editable} reload={reload} toast={toast} />}
    {tab === 'reconcile' && <ConfirmPanel id={id} receipt={receipt} reconciliation={reconciliation} editable={editable} reload={reload} toast={toast} />}
  </>;
}
