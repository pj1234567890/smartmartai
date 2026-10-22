import { useEffect, useRef, useState } from 'react';
import { api } from '../../services/api';
import { Badge, Button, Card, EmptyState, Modal, Skeleton } from '../common/Ui';

const statusLabel = { ISSUED: 'Label printed', SCANNED: 'Scanned', VERIFIED_SHORT: 'Verified short', REJECTED: 'Rejected' };
const statusTone = { ISSUED: 'neutral', SCANNED: 'success', VERIFIED_SHORT: 'warning', REJECTED: 'danger' };

let readerPromiseCache = null;
const loadReader = () => {
  if (!readerPromiseCache) readerPromiseCache = import('@zxing/browser').then((m) => m.BrowserMultiFormatReader);
  return readerPromiseCache;
};

/** Camera scanning of carton labels with manual fallback; the camera is fully controlled by the manager. */
export function CartonScanner({ id, cartons, reconciliation, editable, reload, toast }) {
  const [scanning, setScanning] = useState(false);
  const [cameraError, setCameraError] = useState('');
  const [lastScan, setLastScan] = useState(null);
  const [manualCode, setManualCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [labels, setLabels] = useState(null);
  const [labelForm, setLabelForm] = useState({ lineId: '', unitsPerCarton: '', count: 1 });
  const [verify, setVerify] = useState(null);
  const videoRef = useRef(null);
  const controlsRef = useRef(null);
  const lines = reconciliation?.lines || [];

  useEffect(() => () => stopCamera(), [id]);

  const stopCamera = () => {
    if (controlsRef.current) { try { controlsRef.current.stop(); } catch { /* already stopped */ } }
    controlsRef.current = null;
    setScanning(false);
  };

  const startCamera = async () => {
    setCameraError('');
    try {
      const Reader = await loadReader();
      const reader = new Reader(300, { delayBetweenScanAttempts: 300 });
      const controls = await reader.decodeFromVideoDevice(undefined, videoRef.current, (result, err, controlsArg) => {
        if (result) handleScan(result.getText());
      });
      controlsRef.current = controls;
      setScanning(true);
    } catch (e) {
      setCameraError(e?.message || 'Camera unavailable. Use manual carton code entry instead.');
      setScanning(false);
    }
  };

  const handleScan = async (code) => {
    if (busy || !code) return;
    setBusy(true);
    try {
      const carton = await api.scanCarton(id, code);
      setLastScan({ ok: true, carton });
      reload();
    } catch (e) {
      setLastScan({ ok: false, message: e.message });
      reload();
    } finally { setBusy(false); }
  };

  const submitManual = (e) => {
    e.preventDefault();
    if (!manualCode.trim()) return;
    handleScan(manualCode.trim());
    setManualCode('');
  };

  const printableLabels = async (e) => {
    e.preventDefault();
    try {
      const result = await api.generateLabels(id, {
        lineId: Number(labelForm.lineId),
        unitsPerCarton: Number(labelForm.unitsPerCarton),
        count: Number(labelForm.count) || 1
      });
      setLabels(result);
      reload();
    } catch (err) { toast(err.message, 'error'); }
  };

  const saveVerify = async (payload) => {
    try { await api.verifyCarton(id, verify.id, payload); toast('Carton updated'); setVerify(null); reload(); }
    catch (err) { toast(err.message, 'error'); }
  };

  const remove = async (carton) => {
    try { await api.removeCarton(id, carton.id); toast('Scan removed'); reload(); } catch (e) { toast(e.message, 'error'); }
  };

  const scanned = cartons.filter((c) => c.status === 'SCANNED' || c.status === 'VERIFIED_SHORT');
  const declaredTotal = scanned.reduce((n, c) => n + (c.status === 'VERIFIED_SHORT' ? (c.acceptedUnits || 0) : c.declaredUnits), 0);

  return <>
    <div className="dashboard-grid">
      <Card className="insight-card">
        <div className="section-heading"><div><p className="eyebrow">SCANNER</p><h2>Scan cartons</h2></div>
          {scanning
            ? <Button variant="secondary" onClick={stopCamera}>Stop camera</Button>
            : <Button onClick={startCamera} disabled={!editable} loading={busy}>Start camera</Button>}
        </div>
        <video ref={videoRef} style={{ width: '100%', borderRadius: '12px', background: '#000', minHeight: scanning ? '240px' : 0 }}
          muted playsInline aria-label="Camera preview" />
        <p className="muted">{scanning ? 'Point the camera at a carton QR label.' : 'Camera scanning requests permission only when started. Requires HTTPS or localhost in most browsers.'}</p>
        {cameraError && <p role="alert" style={{ color: '#c0392b' }}>{cameraError}</p>}
        {lastScan && <div className="priority-list">
          {lastScan.ok
            ? <li><span className="signal success" /><p><b>{lastScan.carton.cartonId}</b><small>{lastScan.carton.productName} · {lastScan.carton.declaredUnits} declared units · batch {lastScan.carton.batchCode || '—'}</small></p></li>
            : <li><span className="signal danger" /><p><b>Scan not accepted</b><small>{lastScan.message}</small></p></li>}
        </div>}
        <form className="table-toolbar" onSubmit={submitManual} style={{ marginTop: '0.75rem' }}>
          <label style={{ display: 'contents' }}>
            <span style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden' }}>Manual carton code</span>
            <input placeholder="Manual carton code (fallback)" value={manualCode} onChange={(e) => setManualCode(e.target.value)} aria-label="Manual carton code" />
          </label>
          <Button variant="secondary" loading={busy}>Register</Button>
        </form>
      </Card>

      <Card className="insight-card">
        <div className="section-heading"><div><p className="eyebrow">PROGRESS</p><h2>Session totals</h2></div></div>
        <div className="priority-list">
          <div><span className="signal success" /><p><b>{scanned.length} cartons</b><small>scanned or verified in this receipt</small></p></div>
          <div><span className="signal success" /><p><b>{declaredTotal} units</b><small>total declared quantity</small></p></div>
          <div><span className="signal warning" /><p><b>{cartons.filter((c) => c.status === 'ISSUED').length} labels</b><small>printed but not yet scanned</small></p></div>
        </div>
        {editable && <form className="modal-form" onSubmit={printableLabels} style={{ marginTop: '0.75rem' }}>
          <label>Line<select value={labelForm.lineId} onChange={(e) => setLabelForm({ ...labelForm, lineId: e.target.value })} required>
            <option value="">Print labels for…</option>
            {lines.filter((l) => l.productId).map((l) => <option key={l.id} value={l.id}>{l.productName || l.description}</option>)}
          </select></label>
          <label>Units per carton<input type="number" min="1" value={labelForm.unitsPerCarton} onChange={(e) => setLabelForm({ ...labelForm, unitsPerCarton: e.target.value })} required /></label>
          <label>Carton count<input type="number" min="1" max="20" value={labelForm.count} onChange={(e) => setLabelForm({ ...labelForm, count: e.target.value })} /></label>
          <Button variant="secondary" type="submit" aria-label="Generate carton QR labels">Generate QR labels</Button>
        </form>}
      </Card>
    </div>

    {labels && <Modal title={`${labels.length} printable label${labels.length > 1 ? 's' : ''}`} onClose={() => setLabels(null)}>
      <p className="muted">Each QR encodes the carton id, product id, batch and declared units per carton. Print at 100% scale; scanning does not verify physical contents - the declared quantity stands until checked.</p>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(240px,1fr))', gap: '12px' }}>
        {labels.map((label) => <Card key={label.cartonId}>
          <img alt={`QR label for carton ${label.cartonId}`} src={`data:image/png;base64,${label.pngBase64}`} style={{ width: '100%' }} />
          <div><b>{label.cartonId}</b><span className="muted label-align"> · {label.unitsPerCarton} units</span></div>
        </Card>)}
      </div>
    </Modal>}

    <Card className="table-card">
      <div className="section-heading"><p className="eyebrow">SCANNED CARTONS</p><h2>Carton records</h2></div>
      {!cartons.length ? <EmptyState title="No cartons yet" text="Generate QR labels for a line, then scan each carton once." />
        : <div className="table-wrap"><table>
          <thead><tr><th>Carton</th><th>Product</th><th>Batch</th><th>Declared</th><th>Accepted</th><th>Status</th><th>Scanned at</th><th>Reason</th><th /></tr></thead>
          <tbody>{cartons.map((c) => <tr key={c.id}>
            <td><b>{c.cartonId}</b></td>
            <td>{c.productName}</td>
            <td>{c.batchCode || '—'}</td>
            <td>{c.declaredUnits}</td>
            <td>{c.acceptedUnits ?? '—'}</td>
            <td><Badge tone={statusTone[c.status]}>{statusLabel[c.status] || c.status}</Badge></td>
            <td>{c.scanTimestamp ? new Date(c.scanTimestamp).toLocaleTimeString() : '—'}</td>
            <td>{c.reason || '—'}</td>
            <td style={{ display: 'flex', gap: '0.25rem' }}>
              {(c.status === 'SCANNED' || c.status === 'VERIFIED_SHORT') && editable && <Button variant="ghost" size="small" onClick={() => setVerify(c)}>Verify</Button>}
              {editable && c.status !== 'ISSUED' && <button className="danger-link" onClick={() => remove(c)} aria-label={`Remove scan ${c.cartonId}`}>Undo</button>}
            </td>
          </tr>)}</tbody>
        </table></div>}
    </Card>

    {verify && <Modal title={`Verify carton ${verify.cartonId}`} onClose={() => setVerify(null)}>
      <VerifyForm carton={verify} busy={busy} onClose={() => setVerify(null)} onSave={saveVerify} />
    </Modal>}
  </>;
}

function VerifyForm({ carton, busy, onClose, onSave }) {
  const [form, setForm] = useState({ acceptedUnits: carton.declaredUnits, rejected: carton.status === 'REJECTED', reason: carton.reason || '' });
  const [error, setError] = useState('');
  const save = (e) => {
    e.preventDefault();
    const units = Number(form.acceptedUnits);
    if (Number.isNaN(units) || units < 0 || units > carton.declaredUnits) { setError(`Units must be between 0 and ${carton.declaredUnits}`); return; }
    if (!form.rejected && units < carton.declaredUnits && !form.reason.trim()) { setError('A verified shortage requires a reason'); return; }
    onSave({ acceptedUnits: units, rejected: form.rejected, reason: form.reason || undefined });
  };
  return <form className="modal-form" onSubmit={save}>
    <p className="muted">Declared {carton.declaredUnits} units. Count the physical units and record the verified amount plus why it differs.</p>
    <label>Verified units in carton<input type="number" min="0" max={carton.declaredUnits} value={form.acceptedUnits} onChange={(e) => setForm({ ...form, acceptedUnits: e.target.value })} required /></label>
    <label className="wide"><input type="checkbox" checked={form.rejected} onChange={(e) => setForm({ ...form, rejected: e.target.checked })} /> Damaged / rejected carton (excluded from stock)</label>
    <label className="wide">Reason<input value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} maxLength={500} /></label>
    {error && <p className="muted wide" role="alert" style={{ color: '#c0392b' }}>{error}</p>}
    <div className="modal-actions wide">
      <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
      <Button loading={busy}>Save verification</Button>
    </div>
  </form>;
}
