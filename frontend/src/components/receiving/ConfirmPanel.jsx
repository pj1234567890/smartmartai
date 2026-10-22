import { useState } from 'react';
import { api } from '../../services/api';
import { Badge, Button, Card, EmptyState, Skeleton } from '../common/Ui';
import { money } from '../../utils/formatters';

const discrepancyLabel = {
  MATCHED: 'Quantities match', SHORTAGE: 'Scanned less than invoiced', SURPLUS: 'Scanned more than invoiced',
  UNRESOLVED: 'Needs review', RESOLVED: 'Accepted', REJECTED: 'Rejected/damaged'
};
const discrepancyTone = { MATCHED: 'success', SHORTAGE: 'danger', SURPLUS: 'warning', UNRESOLVED: 'danger', RESOLVED: 'success', REJECTED: 'warning' };

export function ConfirmPanel({ id, receipt, reconciliation, editable, reload, toast }) {
  const [busy, setBusy] = useState(false);
  const [confirmed, setConfirmed] = useState(null);
  const reconcile = async () => { setBusy(true); try { await api.reconcile(id); toast('Reconciliation recalculated'); reload(); } catch (e) { toast(e.message, 'error'); } finally { setBusy(false); } };

  const confirm = async () => {
    setBusy(true);
    try {
      const result = await api.confirmReceipt(id);
      setConfirmed(result);
      toast('Stock committed successfully');
      reload();
    } catch (e) { toast(e.message, 'error'); } finally { setBusy(false); }
  };

  if (!reconciliation) return <Skeleton count={3} className="row-skeleton" />;
  if (confirmed) return <ConfirmedSummary result={confirmed} />;

  const blockers = reconciliation.blockers || [];
  return <>
    <Card className="insight-card">
      <div className="section-heading"><div><p className="eyebrow">RECONCILIATION</p><h2>Expected vs scanned</h2></div>
        <Button variant="secondary" onClick={reconcile} loading={busy} disabled={!editable || receipt.status === 'CONFIRMED'}>Recalculate</Button>
      </div>
      {!reconciliation.lines?.length ? <EmptyState title="Nothing to reconcile" text="Add invoice line items and scan cartons first." />
        : <div className="table-wrap"><table>
          <thead><tr><th>Product</th><th>Batch</th><th>Invoiced</th><th>Scanned</th><th>Accepted</th><th>Difference</th><th>Expiry</th><th>Status</th></tr></thead>
          <tbody>{reconciliation.lines.map((line) => {
            const difference = line.expectedUnits != null && line.scannedQuantity != null ? line.scannedQuantity - line.expectedUnits : null;
            return <tr key={line.id}>
              <td><b>{line.productName || line.description}</b></td>
              <td>{line.batchCode || '—'}</td>
              <td>{line.expectedUnits ?? '—'}</td>
              <td>{line.scannedQuantity ?? 0}</td>
              <td>{line.acceptedQuantity ?? '—'}</td>
              <td>{difference == null ? '—' : difference === 0 ? <Badge tone="success">Even</Badge> : <Badge tone={difference > 0 ? 'warning' : 'danger'}>{difference > 0 ? `+${difference} extra` : `${difference} short`}</Badge>}</td>
              <td>{line.expiryDate || '—'}</td>
              <td><Badge tone={discrepancyTone[line.discrepancyStatus]}>{discrepancyLabel[line.discrepancyStatus] || line.discrepancyStatus}</Badge>{line.resolutionReason && <small>{line.resolutionReason}</small>}</td>
            </tr>;
          })}</tbody>
        </table></div>}
    </Card>

    <Card className="insight-card">
      <div className="section-heading"><div><p className="eyebrow">STOCK COMMIT</p><h2>Confirmation checklist</h2></div></div>
      {blockers.length === 0
        ? <>
          <p className="muted">All validations passed. Confirming will add {reconciliation.lines.reduce((n, l) => n + (l.acceptedQuantity || 0), 0)} units across {receipt.scannedCartonCount || reconciliation.scannedCartons} cartons to the batches above, exactly once, with an audited stock movement for every line. Rejected units are excluded.</p>
          <Button onClick={confirm} loading={busy} disabled={receipt.status === 'CONFIRMED'}>Confirm &amp; commit stock</Button>
        </>
        : <div className="priority-list">
          {blockers.map((b) => <div key={b}><span className="signal danger" /><p><b>Action needed</b><small>{b}</small></p></div>)}
          <Button variant="secondary" onClick={confirm} loading={busy} disabled>Confirm blocked until resolved</Button>
        </div>}
    </Card>
  </>;
}

function ConfirmedSummary({ result }) {
  return <Card className="insight-card">
    <div className="section-heading"><div><p className="eyebrow">STOCK COMMITTED</p><h2>{result.reference}</h2></div></div>
    <p className="muted">Receipt confirmed{result.confirmedAt ? ` at ${new Date(result.confirmedAt).toLocaleString()}` : ''}. Committed movements:</p>
    <div className="table-wrap"><table>
      <thead><tr><th>Product</th><th>Batch</th><th>Units added</th><th>Batch total</th><th>Inventory total</th></tr></thead>
      <tbody>{(result.committedStock || []).map((m, i) => <tr key={i}>
        <td><b>{m.productName}</b><small>Product #{m.productId}</small></td>
        <td>{m.batchCode || '—'}</td>
        <td>+{m.acceptedQuantity}</td>
        <td>{m.newBatchQuantity ?? '—'}</td>
        <td>{m.newInventoryQuantity ?? '—'}</td>
      </tr>)}</tbody>
    </table></div>
    <p className="muted" style={{ marginTop: '0.75rem' }}>Confirming again is safe - the receipt is locked and stock cannot be added twice.</p>
  </Card>;
}
