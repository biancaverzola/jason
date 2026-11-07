!nascer.

+!nascer
  <- .create_agent(certo, "filho_certo.asl");
     .print("criei o certo");
     .create_agent(errado, "filho_errado.asl");
     .print("criei o errado").

-!nascer[error_msg(M)]
  <- .print("falhou ao criar: ", M).
