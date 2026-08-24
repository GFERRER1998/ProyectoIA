export type Source = {
  sourceKey: string;
  chunkIndex: string;
  score: number;
};

export type ChatResponse = {
  answer: string;
  sources: Source[];
  session_id: string;
  model: string;
};

export type Document = {
  documentId: string;
  fileName: string;
  contentType: string;
  size: number;
  status: "PENDING" | "PROCESSING" | "READY" | "ERROR";
  createdAt: string;
  updatedAt: string;
};

export type SessionSummary = {
  sessionId: string;
  title: string;
  lastMessagePreview: string;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
};

export type SessionMessage = {
  role: "user" | "assistant";
  content: string;
  timestamp: string | null;
};

export type SessionDetail = SessionSummary & {
  messages: SessionMessage[];
};

export type UploadPreparation = {
  documentId: string;
  objectKey: string;
  uploadUrl: string;
};

export type ViewUrl = {
  documentId: string;
  fileName: string;
  viewUrl: string;
  expiresIn: number;
};
