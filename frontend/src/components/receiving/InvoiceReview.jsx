import { useState } from 'react';
import { api } from '../../services/api';
import { Badge, Button, Card, EmptyState, Modal } from '../common/Ui';

const matchTone = { MANUAL: 'success', SKU: 'success', PRODUCT_ID: 'success', NAME: 'success', UNMATCHED: 'danger' };
const matchLabel = { MANUAL: 'Manual match', SKU: 'Matched by code', PRODUCT_ID: 'Matched by id', NAME: 'Matched by name', UNMATCHED: 'Unmatched' };
const discrepancyLabel = {
  MATCHED: 'Quantities match', SHORTAGE: 'Scanned less than invoiced', SURPLUS: 'Scanned more than invoiced',
  UNRESOLVED: 'Needs review', RESOLVED: 'Accepted', REJECTED: 'Rejected/damaged'
};
const discrepancyTone = { MATCHED: 'success', SHORTAGE: 'danger', SURPLUS: 'warning', UNRESOLVED: 'danger', RESOLVED: 'success', REJECTED: 'warning' };

/** Editable invoice review: managers correct extracted values before any reconciliation happens. */
export function InvoiceReview({ id, receipt, document, reconciliation, editable, reload, toast }) {
  const [products, setProducts] = useState(null);
  const [editing, setEditing] = useState(null);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uploadError, setUploadError] = useState('');
  const lineMap = Object.fromEntries((reconciliation?.lines || []).map((l) => [l.id, l]));

  const loadProducts = () => {
    if (products) return;
    api.products().then(setProducts).catch((e) => toast(e.message, 'error'));
  };
  const onOpen = (line) => { loadProducts(); setEditing(lineMap[line.id]); };
  const onAdd = () => { loadProducts(); setAdding(true); };

  const upload = async (file) => {
    if (!file) return;
    const ext = (file.name.split('.').pop() || '').toLowerCase();
    if (!['jpg', 'jpeg', 'png', 'pdf'].includes(ext)) { setUploadError('Upload a JPG, JPEG, PNG photo or a PDF invoice'); return; }
    if (file.size > 10 * 1024 * 1024) { setUploadError('The invoice must be at most 10 MB'); return; }
    setBusy(true); setUploadError('');
    try {
      await api.uploadInvoice(id, file);
      toast('Invoice uploaded. Extracting information…');
      await retryExtract();
    } catch (err) { setUploadError(err.message); } finally { setBusy(false); }
  };

  const retryExtract = async () => {
    setBusy(true);
    try { await api.extractInvoice(id); toast('Extraction finished - review the values below'); reload(); }
    catch (err) { toast(err.message, 'error'); reload(); }
    finally { setBusy(false); }
  };

  const saveLine = async (payload) => {
    setBusy(true);
    try {
      if (payload.createProduct) await api.createProductForReceiptLine(id, payload.lineId, payload.createProduct);
      else await api.updateReceiptLine(id, payload.lineId, payload.body);
      toast(payload.createProduct ? 'Catalogue product created and linked to invoice line' : 'Line updated');
      setEditing(null); setAdding(false); reload();
    }
    catch (err) { toast(err.message, 'error'); } finally { setBusy(false); }
  };

  return <>
    <Card className="insight-card">
      <div className="section-heading"><div><p className="eyebrow">SUPPLIER INVOICE</p><h2>Upload &amp; extract</h2></div>
        {editable && <div style={{ display: 'flex', gap: '0.5rem' }}>
          <label className="btn btn-secondary" style={{ cursor: 'pointer' }} aria-label="Upload invoice file">
            Upload invoice input{busy ? '' : <input type="file" accept=".jpg,.jpeg,.png,.pdf" style={{ display: 'none' }}
              onChange={(e) => { upload(e.target.files?.[0]); e.target.value = ''; }} disabled={busy} aria-invalid={!!uploadError} />}
          </label>
          {document && <Button variant="ghost" loading={busy} onClick={retryExtract} disabled={!editable}>Retry extraction</Button>}
        </div>}
      </div>
      <p className="muted">Photograph a printed invoice (JPG/PNG) or upload a digital PDF. Searchable PDFs are read directly; photos go through local OCR when installed. Nothing touches stock until confirmation.</p>
      {uploadError && <p className="muted" role="alert" style={{ color: '#c0392b' }}>{uploadError}</p>}
      {document ? <ul className="priority-list">
        <li><span className={`signal ${document.processingStatus === 'FAILED' ? 'danger' : document.processingStatus === 'COMPLETED' ? 'success' : 'warning'}`} />
          <p><b>{document.originalFilename}</b><small>{document.sourceType} · status: {document.processingStatus}</small></p></li>
        {document.warnings && <li><span className="signal warning" /><p><b>Extraction warnings</b><small>{document.warnings}</small></p></li>}
        {document.error && <li><span className="signal danger" /><p><b>Extraction failed</b><small>{document.error} You can retry or add lines manually.</small></p></li>}
      </ul> : <p className="muted">No invoice uploaded yet{editable ? ' - you can also skip extraction and add lines manually below.' : '.'}</p>}
    </Card>

    <Card className="table-card">
      <div className="section-heading"><div><p className="eyebrow">INVOICE LINES</p><h2>Reviewed line items</h2></div>
        {editable && <Button variant="secondary" onClick={onAdd}>Add line manually</Button>}
      </div>
      {!reconciliation?.lines?.length ? <EmptyState title="No invoice lines yet" text="Upload an invoice and run extraction, or add the lines manually." />
        : <div className="table-wrap"><table>
          <thead><tr><th>Product</th><th>Invoice qty</th><th>Expected units</th><th>Unit cost</th><th>Batch</th><th>Expiry</th><th>Match</th><th>Status</th><th /></tr></thead>
          <tbody>{reconciliation.lines.map((line) => <tr key={line.id}>
            <td><b>{line.description}</b>{line.productName && <small>→ {line.productName}</small>}</td>
            <td>{line.invoiceQuantity ?? '—'} {line.unitOfMeasure || ''}{line.originalQuantity != null && line.originalQuantity !== line.invoiceQuantity && <small>(was {line.originalQuantity})</small>}</td>
            <td>{line.expectedUnits ?? '—'}{line.conversionError && <small role="status">{line.conversionError}</small>}</td>
            <td>{line.unitCost ?? '—'}</td>
            <td>{line.batchCode || '—'}</td>
            <td>{line.expiryDate || '—'}{line.originalExpiryDate && line.originalExpiryDate !== line.expiryDate && <small>(was {line.originalExpiryDate})</small>}</td>
            <td><Badge tone={matchTone[line.matchStatus]}>{matchLabel[line.matchStatus]}</Badge></td>
            <td><Badge tone={discrepancyTone[line.discrepancyStatus]}>{discrepancyLabel[line.discrepancyStatus] || line.discrepancyStatus}</Badge></td>
            <td>{editable && <Button variant="ghost" size="small" onClick={() => onOpen(line)}>Edit</Button>}</td>
          </tr>)}</tbody>
        </table></div>}
    </Card>

    {(editing || adding) && <LineForm products={products} line={editing} busy={busy} onClose={() => { setEditing(null); setAdding(false); }} onSave={saveLine} toast={toast} />}
  </>;
}

function LineForm({ products, line, busy, onClose, onSave, toast }) {
  const packUnit = ['PACK', 'PACKS', 'PKT'].includes((line?.unitOfMeasure || '').toUpperCase());
  const cartonUnit = ['CARTON', 'CARTONS', 'CASE', 'CASES', 'BOX', 'BOXES'].includes((line?.unitOfMeasure || '').toUpperCase());
  const conversionLabel = packUnit ? 'Stock units per pack' : cartonUnit ? 'Stock units per carton' : 'Stock units per invoice unit';
  const base = line || { description: '', productId: '', invoiceQuantity: '', unitOfMeasure: 'UNIT', unitsPerCarton: '', unitCost: '', batchCode: '', expiryDate: '', acceptedQuantity: '', rejectedQuantity: '', resolutionReason: '' };
  const [form, setForm] = useState({
    description: base.description || '',
    productId: base.productId || '',
    invoiceQuantity: base.invoiceQuantity ?? '',
    unitOfMeasure: base.unitOfMeasure || 'UNIT',
    unitsPerCarton: base.unitsPerCarton ?? '',
    unitCost: base.unitCost ?? '',
    batchCode: base.batchCode || '',
    expiryDate: base.expiryDate || '',
    acceptedQuantity: base.acceptedQuantity ?? '',
    rejectedQuantity: base.rejectedQuantity ?? '',
    resolutionReason: base.resolutionReason || ''
  });
  const [error, setError] = useState('');
  const [createNew, setCreateNew] = useState(false);
  const [newProduct, setNewProduct] = useState({ name: line?.description || '', brand: '', category: '', price: '', aisle: '', shelf: '', description: '' });
  const set = (e) => setForm({ ...form, [e.target.name]: e.target.value });
  const setNew = (e) => setNewProduct({ ...newProduct, [e.target.name]: e.target.value });
  const save = (e) => {
    e.preventDefault();
    if (createNew) {
      if (!newProduct.name.trim() || !newProduct.brand.trim() || !newProduct.category.trim() || !(Number(newProduct.price) > 0)) {
        setError('Enter a product name, brand, category, and selling price greater than zero'); return;
      }
      onSave({ lineId: line.id, createProduct: { ...newProduct, name: newProduct.name.trim(), brand: newProduct.brand.trim(), category: newProduct.category.trim(), price: Number(newProduct.price) } });
      return;
    }
    if (line && !line.productId && !form.productId) { setError('Select the matching SmartMart product'); return; }
    onSave({
      lineId: line?.id || 0,
      body: {
        productId: form.productId ? Number(form.productId) : undefined,
        invoiceQuantity: form.invoiceQuantity === '' ? undefined : Number(form.invoiceQuantity),
        unitOfMeasure: form.unitOfMeasure || undefined,
        unitsPerCarton: form.unitsPerCarton === '' ? undefined : Number(form.unitsPerCarton),
        unitCost: form.unitCost === '' ? undefined : Number(form.unitCost),
        batchCode: form.batchCode || undefined,
        expiryDate: form.expiryDate || undefined,
        acceptedQuantity: form.acceptedQuantity === '' ? undefined : Number(form.acceptedQuantity),
        rejectedQuantity: form.rejectedQuantity === '' ? undefined : Number(form.rejectedQuantity),
        resolutionReason: form.resolutionReason || undefined
      }
    });
  };
  return <Modal title={line ? `Edit line · ${line.description}` : 'Add invoice line manually'} onClose={onClose}>
    <form className="modal-form product-form" onSubmit={save}>
      <label className="wide">Invoice description<input name="description" value={form.description} onChange={set} disabled readOnly title="Descriptions are kept as printed on the invoice" /></label>
      {line && !line.productId && <div className="wide" style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
        <Button type="button" variant={!createNew ? 'secondary' : 'ghost'} onClick={() => { setCreateNew(false); setError(''); }}>Select existing product</Button>
        <Button type="button" variant={createNew ? 'secondary' : 'ghost'} onClick={() => { setCreateNew(true); setError(''); }}>Create new catalogue product</Button>
      </div>}
      {createNew ? <>
        <p className="muted wide">This creates a catalogue entry and links it to this invoice line. It does not add stock; inventory changes only when the receipt is confirmed. Enter the selling price, which may differ from the invoice unit cost.</p>
        <label>Product name<input name="name" value={newProduct.name} onChange={setNew} required maxLength="255" /></label>
        <label>Brand<input name="brand" value={newProduct.brand} onChange={setNew} required maxLength="100" /></label>
        <label>Category<input name="category" value={newProduct.category} onChange={setNew} required maxLength="100" /></label>
        <label>Selling price<input name="price" type="number" min="0.01" step="0.01" value={newProduct.price} onChange={setNew} required /></label>
        <label>Aisle<input name="aisle" value={newProduct.aisle} onChange={setNew} maxLength="50" /></label>
        <label>Shelf<input name="shelf" value={newProduct.shelf} onChange={setNew} maxLength="50" /></label>
        <label className="wide">Description<textarea name="description" value={newProduct.description} onChange={setNew} rows="2" maxLength="1000" /></label>
      </> : <>
      <label>SmartMart product<select name="productId" value={form.productId} onChange={set}>
        <option value="">{products ? 'Select a product…' : 'Loading products…'}</option>
        {(products?.content || []).map((p) => <option key={p.id} value={p.id}>{p.name} · {p.brand}</option>)}
      </select></label>
      <label>Invoice quantity<input type="number" min="0" name="invoiceQuantity" value={form.invoiceQuantity} onChange={set} /></label>
      <label>Unit of measure<select name="unitOfMeasure" value={form.unitOfMeasure} onChange={set}>
        {['UNIT', 'CARTON', 'CASE', 'BOX', 'PACK', 'PKT', 'KG', 'L', ''].map((u) => <option key={u || 'none'} value={u}>{u || 'blank'}</option>)}
      </select></label>
      <label>{conversionLabel} <small>(enter a verified conversion; leave blank if unknown)</small><input type="number" min="0" name="unitsPerCarton" value={form.unitsPerCarton} onChange={set} /></label>
      <label>Unit cost<input type="number" min="0" step="0.01" name="unitCost" value={form.unitCost} onChange={set} /></label>
      <label>Batch / lot code<input name="batchCode" value={form.batchCode} onChange={set} maxLength={100} /></label>
      <label>Expiry date<input type="date" name="expiryDate" value={form.expiryDate} onChange={set} /></label>
      <label>Accepted quantity (stock)<input type="number" min="0" name="acceptedQuantity" value={form.acceptedQuantity} onChange={set} /></label>
      <label>Rejected / damaged units<input type="number" min="0" name="rejectedQuantity" value={form.rejectedQuantity} onChange={set} /></label>
      <label className="wide">Resolution reason <small>(required when accepting at variance)</small><input name="resolutionReason" value={form.resolutionReason} onChange={set} maxLength={500} /></label>
      </>}
      {error && <p className="muted wide" role="alert" style={{ color: '#c0392b' }}>{error}</p>}
      <div className="modal-actions wide">
        <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
        <Button loading={busy}>{createNew ? 'Create product and link line' : 'Save line'}</Button>
      </div>
    </form>
  </Modal>;
}
