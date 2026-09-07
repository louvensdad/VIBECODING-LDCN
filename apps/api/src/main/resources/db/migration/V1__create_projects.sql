CREATE TABLE projects (
  id UUID PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(1000),
  original_idea TEXT NOT NULL,
  status VARCHAR(30) NOT NULL,
  current_phase VARCHAR(100),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
