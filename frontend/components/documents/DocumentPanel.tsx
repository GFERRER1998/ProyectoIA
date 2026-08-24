"use client";

import { useEffect, useRef, useState, type ChangeEvent, type DragEvent } from "react";
import { deleteDocument, getDocumentViewUrl, listDocuments, uploadDocument } from "@/lib/api/client";
import type { Document } from "@/lib/api/types";

const MAX_FILE_SIZE = 50 * 1024 * 1024;

/** Renderiza carga, estados y visualización de documentos PDF del usuario. */
export function DocumentPanel() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  /** Carga metadata del usuario y deja la lista preparada para actualizarse. */
  async function loadDocuments() {
    try {
      setDocuments(await listDocuments());
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : "No se pudieron cargar los documentos.");
    }
  }

  useEffect(() => {
    void listDocuments().then(setDocuments).catch((caught: unknown) => {
      setError(caught instanceof Error ? caught.message : "No se pudieron cargar los documentos.");
    });
  }, []);

  /** Actualiza estados pendientes hasta que el procesamiento termina. */
  useEffect(() => {
    if (!documents.some((document) => document.status === "PENDING" || document.status === "PROCESSING")) return;
    const interval = window.setInterval(() => {
      void listDocuments().then(setDocuments).catch((caught: unknown) => {
        setError(caught instanceof Error ? caught.message : "No se pudo actualizar el estado del documento.");
      });
    }, 5000);
    return () => window.clearInterval(interval);
  }, [documents]);

  /** Valida un PDF antes de solicitar una URL prefirmada al backend. */
  function validate(file: File) {
    if (file.type !== "application/pdf" || !file.name.toLowerCase().endsWith(".pdf")) return "Solo se permiten archivos PDF.";
    if (file.size <= 0 || file.size > MAX_FILE_SIZE) return "El PDF debe pesar entre 1 byte y 50 MB.";
    return "";
  }

  /** Sube un archivo validado y actualiza la lista con el estado inicial. */
  async function handleFile(file: File) {
    const validationError = validate(file);
    if (validationError) { setError(validationError); return; }
    setError("");
    setMessage("Preparando subida...");
    setUploading(true);
    try {
      await uploadDocument(file);
      setMessage("Documento subido. Procesando contenido...");
      await loadDocuments();
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : "No se pudo subir el documento.");
    } finally {
      setUploading(false);
    }
  }

  /** Procesa archivos seleccionados desde el input nativo. */
  function handleInput(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (file) void handleFile(file);
    event.target.value = "";
  }

  /** Procesa el primer archivo soltado en la zona drag and drop. */
  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDragging(false);
    const file = event.dataTransfer.files[0];
    if (file) void handleFile(file);
  }

  /** Solicita una URL temporal y abre el PDF en una pestaña independiente. */
  async function viewDocument(documentId: string) {
    try {
      const result = await getDocumentViewUrl(documentId);
      window.open(result.viewUrl, "_blank", "noopener,noreferrer");
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : "No se pudo abrir el documento.");
    }
  }

  /** Confirma y elimina un documento propio desde la biblioteca. */
  async function removeDocument(document: Document) {
    if (!window.confirm(`¿Eliminar "${document.fileName}"? Esta acción no se puede deshacer.`)) return;
    setError("");
    try {
      await deleteDocument(document.documentId);
      setDocuments((current) => current.filter((item) => item.documentId !== document.documentId));
      setMessage("Documento eliminado.");
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : "No se pudo eliminar el documento.");
    }
  }

  return (
    <section className="documents-panel">
      <header className="feature-header"><div><p className="eyebrow">Biblioteca privada</p><h1>Documentos</h1><p>Los PDFs que alimentan tus conversaciones.</p></div><span className="document-count">{documents.length} archivos</span></header>
      <div className={dragging ? "dropzone dragging" : "dropzone"} onDragEnter={(event) => { event.preventDefault(); setDragging(true); }} onDragLeave={(event) => { event.preventDefault(); setDragging(false); }} onDragOver={(event) => event.preventDefault()} onDrop={handleDrop}>
        <div className="drop-icon">＋</div><h2>Suelta tu PDF aquí</h2><p>o selecciona un archivo de hasta 50 MB</p>
        <button className="secondary-button" disabled={uploading} onClick={() => inputRef.current?.click()} type="button">{uploading ? "Subiendo..." : "Seleccionar archivo"}</button>
        <input accept="application/pdf,.pdf" hidden onChange={handleInput} ref={inputRef} type="file" />
      </div>
      {message && <p className="success-message">{message}</p>}
      {error && <p className="form-error document-error">{error}</p>}
       <div className="document-list">{documents.map((document) => <article className="document-row" key={document.documentId}><div className="pdf-badge">PDF</div><div className="document-info"><strong>{document.fileName}</strong><small>{formatSize(document.size)} · {formatStatus(document.status)}</small></div><button className="text-button" disabled={document.status !== "READY"} onClick={() => void viewDocument(document.documentId)} type="button">Ver</button><button className="text-button" onClick={() => void removeDocument(document)} type="button">Eliminar</button></article>)}{documents.length === 0 && <p className="muted-copy">Todavía no has subido documentos.</p>}</div>
    </section>
  );
}

/** Convierte bytes a una unidad legible para la lista de documentos. */
function formatSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Traduce el estado técnico a una etiqueta breve para el usuario. */
function formatStatus(status: Document["status"]) {
  return ({ PENDING: "pendiente", PROCESSING: "procesando", READY: "disponible", ERROR: "error" })[status];
}
