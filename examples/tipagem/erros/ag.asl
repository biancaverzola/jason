// agente com vários erros de tipo de propósito (cada erro está marcado com ERRO)

{ type(pos(number, number), localizacao(atom, pos)) }
{ type(caminho(list(pos))) }
{ type(ligado(boolean)) }
{ type(nome(string)) }
{ type(idade(number)) }
{ type(cor(cores)) }                   // ERRO: o tipo "cores" não existe
{ type(idade(string)) }                // ERRO: idade/1 já foi declarado com outro tipo

localizacao(robo, pos(3, "cinco")).    // ERRO: "cinco" não é number
localizacao(robo, 7).                  // ERRO: 7 não é um pos
localizacao(robo, ponto(1, 2)).        // ERRO: ponto(1,2) não é um pos
caminho([pos(1,2), forno]).            // ERRO: forno não é um pos
ligado(sim).                           // ERRO: sim não é boolean
nome(bob).                             // ERRO: bob é atom, não string
idade(30)[source(self)].               // ok: anotações são ignoradas
idade(bob, 30).                        // ok: idade/2 não foi declarado (não é verificado)
velho(P) :- idade(I) & I > 60 & nome(I).   // ERRO: I é number, nome espera string

!go.

+!go
   : idade(I) & nome(Nm)
  <- N = I + 1;                        // ok
     X = Nm * 2;                       // ERRO: Nm é string, não dá para multiplicar
     if (idade(A)) { +nome(A) };       // ERRO: A é number, nome espera string
     .send(bob, tell, idade("x"));     // não verificado (conteúdo do .send ficou para depois)
     Z = "texto";
     +idade(Z).                        // ERRO: Z é string, idade espera number
