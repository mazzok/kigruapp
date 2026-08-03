export type ParentDirectoryScope = 'CHILD' | 'PARENT' | 'FAMILY';

export interface ParentDirectoryColumn {
  key: string;
  label: string;
  scope: ParentDirectoryScope;
}

export interface ParentDirectoryParent {
  values: Record<string, string>;
}

export interface ParentDirectoryChild {
  name: string | null;
  entryDate: string | null;
  exitDate: string | null;
}

export interface ParentDirectoryFamily {
  familyId: string;
  isOwnFamily: boolean;
  children: ParentDirectoryChild[];
  parents: ParentDirectoryParent[];
  address: string | null;
}

export interface ParentDirectoryGroup {
  groupInstanceId: string;
  groupName: string | null;
  families: ParentDirectoryFamily[];
}

export interface ParentDirectory {
  semesterId: string | null;
  columns: ParentDirectoryColumn[];
  groups: ParentDirectoryGroup[];
}
