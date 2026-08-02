export interface ParentDirectoryParent {
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  phone: string | null;
}

export interface ParentDirectoryFamily {
  familyId: string;
  isOwnFamily: boolean;
  children: (string | null)[];
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
  groups: ParentDirectoryGroup[];
}
