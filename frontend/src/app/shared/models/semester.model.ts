export interface Semester {
  id: string;
  start: string;
  end: string;
  createdAt: string;
}

export interface CreateSemesterRequest {
  start: string;
  end: string;
}
